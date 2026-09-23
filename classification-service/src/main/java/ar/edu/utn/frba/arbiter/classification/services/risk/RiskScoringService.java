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
 * Weighted average of the insurer's active factors, normalized by the total evaluable weight so it
 * stays in [0.0, 1.0] for any config. A support signal for the analyst; it never gates anything.
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
                // Excluded so it doesn't dilute claims it doesn't apply to.
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

        // Nothing evaluable: not scored, rather than a fabricated 0.0/LOW.
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
