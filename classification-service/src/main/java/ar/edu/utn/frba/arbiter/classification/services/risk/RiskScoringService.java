package ar.edu.utn.frba.arbiter.classification.services.risk;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.common.dto.RiskBreakdownItem;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Computes the parallel fraud/risk score for a claim as a weighted sum of the active factors
 * configured for the insurer (see {@link BusinessRules.ScoringConfig}). Runs downstream of the
 * classification, off the same {@link RiskContext} the orchestrator already assembled — it feeds
 * the analyst's fraud-gauge and never gates or automates anything on its own.
 *
 * <p>The weighting is normalized by the total active weight, so the score stays in [0.0, 1.0]
 * regardless of how many factors an insurer turns on or how it scales its weights — that's what
 * makes the same engine work for any insurer's config.
 */
@Service
public class RiskScoringService {

    private static final Logger log = LoggerFactory.getLogger(RiskScoringService.class);

    private final Map<String, RiskFactorEvaluator> evaluatorsById;

    public RiskScoringService(List<RiskFactorEvaluator> evaluators) {
        this.evaluatorsById = evaluators.stream()
                .collect(Collectors.toMap(RiskFactorEvaluator::factorId, Function.identity()));
    }

    public RiskScore score(RiskContext context) {
        BusinessRules.ScoringConfig config = context.rules() == null ? null : context.rules().scoringConfig();
        if (config == null || config.factors() == null || config.factors().isEmpty()) {
            log.info("[RiskScoring] No scoring config for branch/claimCause — not scored (sin scorear)");
            return RiskScore.notScored();
        }

        List<RiskBreakdownItem> breakdown = new ArrayList<>();
        double weightedSum = 0.0;
        double totalWeight = 0.0;

        for (BusinessRules.ScoringConfig.FactorWeight factor : config.factors()) {
            RiskFactorEvaluator evaluator = evaluatorsById.get(factor.factorId());
            if (evaluator == null) {
                log.warn("[RiskScoring] Active factor '{}' has no evaluator — skipping", factor.factorId());
                continue;
            }

            RiskFactorEvaluator.Contribution contribution = evaluator.evaluate(context);
            if (!contribution.evaluable()) {
                // Missing data for this factor: exclude it from the weighted average so it doesn't
                // dilute the claims it doesn't apply to (e.g. image factors on an image-less claim).
                log.debug("[RiskScoring] Factor '{}' not evaluable — excluded: {}",
                        factor.factorId(), contribution.rationale());
                continue;
            }

            double weighted = contribution.score() * factor.weight();
            weightedSum += weighted;
            totalWeight += factor.weight();

            breakdown.add(new RiskBreakdownItem(
                    factor.factorId(),
                    contribution.score(),
                    factor.weight(),
                    weighted,
                    contribution.rationale()));
        }

        // No factor could be evaluated: treat as "sin scorear" rather than a fabricated 0.0/LOW.
        if (totalWeight == 0.0) {
            log.info("[RiskScoring] No factor was evaluable — not scored (sin scorear)");
            return RiskScore.notScored();
        }

        double score = weightedSum / totalWeight;
        RiskBand band = resolveBand(score, config.bands());

        log.info("[RiskScoring] score={} band={} factors={} config={}",
                String.format("%.3f", score), band, breakdown.size(), config.id());
        return new RiskScore(true, score, band, List.copyOf(breakdown), config.id());
    }

    /** Highest band whose {@code minScoreInclusive <= score}; falls back to the lowest-threshold band. */
    private RiskBand resolveBand(double score, List<BusinessRules.ScoringConfig.Band> bands) {
        if (bands == null || bands.isEmpty()) {
            return RiskBand.LOW;
        }
        return bands.stream()
                .filter(b -> score >= b.minScoreInclusive())
                .max(Comparator.comparingDouble(BusinessRules.ScoringConfig.Band::minScoreInclusive))
                .map(BusinessRules.ScoringConfig.Band::band)
                .orElseGet(() -> bands.stream()
                        .min(Comparator.comparingDouble(BusinessRules.ScoringConfig.Band::minScoreInclusive))
                        .map(BusinessRules.ScoringConfig.Band::band)
                        .orElse(RiskBand.LOW));
    }
}
