package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.LlmClient;
import ar.edu.utn.frba.arbiter.classification.config.LlmProperties;
import ar.edu.utn.frba.arbiter.classification.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.exceptions.InvalidClassificationException;
import ar.edu.utn.frba.arbiter.classification.models.entities.CaseClassification;
import ar.edu.utn.frba.arbiter.classification.models.entities.ImageForensicReportJsonConverter;
import ar.edu.utn.frba.arbiter.classification.models.entities.LlmAnalysis;
import ar.edu.utn.frba.arbiter.classification.models.entities.LlmReason;
import ar.edu.utn.frba.arbiter.classification.models.entities.RiskAnalysis;
import ar.edu.utn.frba.arbiter.classification.models.entities.RuleResult;
import ar.edu.utn.frba.arbiter.classification.models.repositories.CaseClassificationRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.CaseOutcomeRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.LlmAnalysisRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.RiskAnalysisRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.RuleResultRepository;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskScore;
import ar.edu.utn.frba.arbiter.common.dto.ClaimResponse;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.common.dto.RuleResultResponse;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Persists every classification and exposes each case's latest result for polling. Append-only
 * audit trail (Disposición SSN 2/2023): nothing here updates a classification once written.
 */
@Service
@RequiredArgsConstructor
public class ClassificationResultsService {

    private static final Logger log = LoggerFactory.getLogger(ClassificationResultsService.class);
    private static final ImageForensicReportJsonConverter FORENSIC_JSON = new ImageForensicReportJsonConverter();

    private final LlmAnalysisRepository llmAnalysisRepository;
    private final CaseClassificationRepository caseClassificationRepository;
    private final RiskAnalysisRepository riskAnalysisRepository;
    private final CaseOutcomeRepository caseOutcomeRepository;
    private final RuleResultRepository ruleResultRepository;
    /** Source of {@code llm_analysis.model}: the configured name would be wrong when another provider answered. */
    private final LlmClient llmClient;
    private final LlmProperties llmProperties;

    @Transactional
    public void saveResult(
            Long caseId,
            ClassificationResponse response,
            ImageForensicReport forensicReport,
            long latencyMs
    ) {
        // A run that reaches here succeeded — clear any failure recorded by a previous attempt,
        // otherwise a case that self-healed on a later retry would still read as failed.
        caseOutcomeRepository.clearClassificationFailure(caseId);

        if (response.deterministicFastTrack()) {
            // No llm_analysis row: the model never ran. The outcome is recorded on the case,
            // which is the only place that can tell "fast tracked" from "not classified yet".
            caseOutcomeRepository.markFastTracked(caseId);
            log.info("[ResultsService] Fast Track recorded for case {}", caseId);
        } else {
            LlmAnalysis analysis = new LlmAnalysis();
            analysis.setCaseId(caseId);
            analysis.setRecommendation(response.classification());
            analysis.setModel(llmClient.model());
            analysis.setPromptVersion(llmProperties.promptVersion());
            analysis.setConfidence(BigDecimal.valueOf(response.confidence()));
            analysis.setLatencyMs((int) latencyMs);
            analysis.setAnalyzedAt(Instant.now());
            analysis.setCauseConsistency(response.causeConsistency());
            analysis.setSuggestedClaimCause(response.suggestedClaimCause());
            analysis.setCauseEvidence(response.causeEvidence());
            response.factors().forEach(analysis::addReason);

            llmAnalysisRepository.save(analysis);
            log.info("[ResultsService] Classification logged for case {} ({})", caseId, response.classification());
        }

        saveRuleResults(caseId, response);

        if (forensicReport != null) {
            caseOutcomeRepository.saveForensicReport(caseId, FORENSIC_JSON.convertToDatabaseColumn(forensicReport));
        }
        saveRiskAnalysis(caseId, response.riskScore());
    }

    /**
     * Both PASS and FAIL are written: the audit is "which rule was evaluated and with what result".
     * {@code rule_id} is null for what isn't an {@code insurer_rule} row (coverage scope, Fast Track criteria).
     */
    private void saveRuleResults(Long caseId, ClassificationResponse response) {
        if (caseId == null || response.ruleFindings() == null || response.ruleFindings().isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        response.ruleFindings().forEach(finding -> {
            RuleResult row = new RuleResult();
            row.setCaseId(caseId);
            row.setRuleId(finding.ruleId());
            row.setRuleType(finding.ruleType());
            row.setResult(finding.result());
            row.setEvaluatedValue(finding.evaluatedValue());
            row.setEvaluatedAt(now);
            ruleResultRepository.save(row);
        });
        log.info("[ResultsService] {} rule_result row(s) written for case {}", response.ruleFindings().size(), caseId);
    }

    /** An unscored claim writes nothing, so the neutral 0.0/LOW is never read as a real LOW band. */
    private void saveRiskAnalysis(Long caseId, RiskScore riskScore) {
        if (riskScore == null || !riskScore.scored()) {
            return;
        }
        RiskAnalysis analysis = new RiskAnalysis();
        analysis.setCaseId(caseId);
        analysis.setRiskScore(BigDecimal.valueOf(riskScore.score()));
        analysis.setRiskBand(riskScore.band());
        analysis.setRiskBreakdown(riskScore.breakdown());
        riskAnalysisRepository.save(analysis);

        // Null when scoring came from the baseline rather than a referente row: the FK would reject it.
        if (riskScore.scoringConfigurationId() != null) {
            caseOutcomeRepository.saveScoringConfiguration(caseId, riskScore.scoringConfigurationId());
        }
    }

    /**
     * Only the latest evaluation of each rule: the table is append-only and every reclassification
     * writes its own set. A rule is identified by {@code (rule_type, rule_id)}, since one run can write
     * several {@code COVERAGE_EXCLUSION} rows.
     */
    @Transactional
    public List<RuleResultResponse> getRuleResults(Long caseId) {
        return ruleResultRepository.findByCaseIdOrderByEvaluatedAtAsc(caseId).stream()
                .collect(Collectors.toMap(
                        r -> r.getRuleType() + "#" + r.getRuleId(),
                        r -> r,
                        // Ascending order, so the later row is the one that stays.
                        (older, newer) -> newer,
                        LinkedHashMap::new))
                .values().stream()
                .map(r -> new RuleResultResponse(
                        r.getId(),
                        r.getRuleType(),
                        r.getResult(),
                        r.getEvaluatedValue(),
                        r.getScoreContribution(),
                        r.getEvaluatedAt()))
                .toList();
    }

    /**
     * Not {@code readOnly}: under cases-service's sweep that mode failed with "No EntityManager with
     * actual transaction available" on flush, even though the method writes nothing.
     */
    @Transactional
    public ClaimResponse getStatus(Long caseId) {
        Optional<LlmAnalysis> analysis = llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(caseId);
        Optional<RiskAnalysis> risk = riskAnalysisRepository.findFirstByCaseIdOrderByIdDesc(caseId);
        CaseOutcomeRepository.CaseOutcome outcome = caseOutcomeRepository.findOutcome(caseId);

        return ClaimResponse.builder()
                .caseId(caseId)
                // Fast Track first: it writes no llm_analysis row, so on reclassification the
                // append-only table would still return the previous run. The flag is rewritten every run.
                .classification(outcome.wasFastTrack()
                        ? Classification.FAST_TRACK
                        : analysis.map(LlmAnalysis::getRecommendation).orElse(null))
                // Double.valueOf, not 1.0: a primitive literal would unbox the null branch (NPE).
                .confidence(outcome.wasFastTrack()
                        ? Double.valueOf(1.0)
                        : analysis.map(a -> a.getConfidence() != null ? a.getConfidence().doubleValue() : null)
                                .orElse(null))
                // On Fast Track, the previous run's model fields would describe a different classification.
                .factors(outcome.wasFastTrack()
                        ? null
                        : analysis.map(a -> a.getReasons().stream().map(LlmReason::getReason).toList())
                                .orElse(null))
                .deterministicFastTrack(outcome.wasFastTrack())
                .forensicReport(FORENSIC_JSON.convertToEntityAttribute(outcome.forensicReport()))
                .riskScore(risk.map(r -> r.getRiskScore().doubleValue()).orElse(null))
                .riskBand(risk.map(RiskAnalysis::getRiskBand).orElse(null))
                .riskBreakdown(risk.map(RiskAnalysis::getRiskBreakdown).orElse(null))
                .insuredName(outcome.insuredName())
                .causeConsistency(outcome.wasFastTrack()
                        ? null : analysis.map(LlmAnalysis::getCauseConsistency).orElse(null))
                .suggestedClaimCause(outcome.wasFastTrack()
                        ? null : analysis.map(LlmAnalysis::getSuggestedClaimCause).orElse(null))
                .causeEvidence(outcome.wasFastTrack()
                        ? null : analysis.map(LlmAnalysis::getCauseEvidence).orElse(null))
                // cases-service's poller uses it to tell this row from one a previous run left behind.
                .analyzedAt(outcome.wasFastTrack()
                        ? null : analysis.map(LlmAnalysis::getAnalyzedAt).orElse(null))
                .build();
    }

    /** Returns the {@code case_classification} id, which cases-service stores on {@code cases.classification_id}. */
    @Transactional
    public Long recordAnalystDecision(Long caseId, AnalystDecisionRequest request) {
        // A Fast Track has no analysis to point at but still needs an analyst's decision.
        Optional<LlmAnalysis> analysis = llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(caseId);
        if (analysis.isEmpty() && !caseOutcomeRepository.findOutcome(caseId).wasFastTrack()) {
            throw new InvalidClassificationException("No classification found for case " + caseId);
        }

        CaseClassification decision = new CaseClassification();
        decision.setLlmAnalysis(analysis.orElse(null));
        decision.setAnalystId(request.analystId());
        decision.setDecision(normalizeDecision(request.decision()));
        decision.setAnalystJustification(request.justification());
        decision.setDecidedAt(Instant.now());
        // Freezes cases.classification_attempts onto the audit row; the column is NOT NULL.
        decision.setClassificationAttempts(
                request.classificationAttempts() == null ? 0 : request.classificationAttempts());

        return caseClassificationRepository.save(decision).getId();
    }

    private String normalizeDecision(String decision) {
        String normalized = decision == null ? "" : decision.trim().toUpperCase();
        return switch (normalized) {
            case "APPROVE", "APROBAR", "YES", "Y" -> "APPROVE";
            case "REJECT", "RECHAZAR", "NO", "N" -> "REJECT";
            default -> normalized;
        };
    }
}
