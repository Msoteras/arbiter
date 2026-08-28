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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Persists every classification and exposes each case's latest result for polling.
 *
 * <p>Replaces the single {@code classification_log} table with the DER's three: the model's
 * recommendation ({@code llm_analysis}), the factors behind it ({@code llm_reason}), and the
 * analyst's verdict ({@code case_classification}). The old shape forced a copy of the whole
 * snapshot onto a second row every time an analyst decided, just so a later read would still
 * find the factors; now the decision points at the analysis and there is one copy of each fact.
 *
 * <p>Still an immutable audit trail (Disposición SSN 2/2023) — nothing here updates a
 * classification once written.
 */
@Service
@RequiredArgsConstructor
public class ClassificationResultsService {

    private static final Logger log = LoggerFactory.getLogger(ClassificationResultsService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    /** Stateless; the report is JSON on cases.forensic_report, not a mapped column any more. */
    private static final ImageForensicReportJsonConverter FORENSIC_JSON = new ImageForensicReportJsonConverter();

    private final LlmAnalysisRepository llmAnalysisRepository;
    private final CaseClassificationRepository caseClassificationRepository;
    private final RiskAnalysisRepository riskAnalysisRepository;
    private final CaseOutcomeRepository caseOutcomeRepository;
    private final RuleResultRepository ruleResultRepository;
    /**
     * Read for {@code llm_analysis.model}, not from a properties record: with more than one
     * provider wired, the configured Ollama model name would be persisted even when Gemini was
     * the one that answered — an audit trail naming a model that never ran.
     */
    private final LlmClient llmClient;
    private final LlmProperties llmProperties;

    @Transactional
    public void saveResult(
            Long caseId,
            ClassificationResponse response,
            ImageForensicReport forensicReport,
            long latencyMs
    ) {
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
     * Audits the hard rules evaluated (today, coverage exclusions) in {@code rule_result} — closes
     * D4c: the table existed with an entity and repository but zero writers. Both PASS and FAIL are
     * written: SSN Disposition 2/2023's audit is "which rule was evaluated and with what result",
     * not just the rejections. {@code rule_id} is a NOT NULL FK to {@code insurer_rule} and travels
     * from the evaluated rule. Only in the flow with a {@code caseId}: the row references
     * {@code cases(id)}, so the isolated flow (no case) doesn't write.
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

    /**
     * Writes a risk_analysis row only when the claim was actually scored. When there's no scoring
     * config the engine returns a neutral 0.0/LOW, but we skip persisting it ("sin scorear") so the
     * read model never presents it as a real LOW band.
     */
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

        // Which configuration computed it (D29). Null when the scoring came from the baseline and
        // not from a referente row: there's nothing to point at, and the FK would reject it.
        if (riskScore.scoringConfigurationId() != null) {
            caseOutcomeRepository.saveScoringConfiguration(caseId, riskScore.scoringConfigurationId());
        }
    }

    /**
     * Latest classification for a case; classification fields stay null until one exists.
     *
     * <p>{@code readOnly} y no plano: con {@code readOnly = true} este método —llamado
     * repetidamente por el sweep de {@code cases-service}, tanto desde una request real como desde
     * el scheduler sin JWT detrás— reventaba con "No EntityManager with actual transaction
     * available for current thread - cannot reliably process 'flush' call" pese a que el
     * interceptor de Spring sí abría la transacción (visible en el stack trace). Sin writes en el
     * método, sacar {@code readOnly} no cambia el comportamiento, solo evita el modo que rompía.
     */
    /** Empty when none ran: a Fast Track resolves on the gate and writes no rows here. */
    @Transactional
    public List<RuleResultResponse> getRuleResults(Long caseId) {
        return ruleResultRepository.findByCaseIdOrderByEvaluatedAtAsc(caseId).stream()
                .map(r -> new RuleResultResponse(
                        r.getId(),
                        r.getRuleType(),
                        r.getResult(),
                        r.getEvaluatedValue(),
                        r.getScoreContribution(),
                        r.getEvaluatedAt()))
                .toList();
    }

    @Transactional
    public ClaimResponse getStatus(Long caseId) {
        Optional<LlmAnalysis> analysis = llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(caseId);
        Optional<RiskAnalysis> risk = riskAnalysisRepository.findFirstByCaseIdOrderByIdDesc(caseId);
        CaseOutcomeRepository.CaseOutcome outcome = caseOutcomeRepository.findOutcome(caseId);

        return ClaimResponse.builder()
                .caseId(caseId)
                // Fast Track wins when present: a Fast Track leaves no llm_analysis row, and that
                // table is append-only, so asking it first would let the PREVIOUS run win. It
                // happens on reclassification (uploading the missing documentation and having the
                // gate resolve Fast Track): the old FALTA_DOCUMENTACION stayed on screen. The flag
                // doesn't have that problem because it's rewritten on every run, so true always
                // means "the last one was Fast Track".
                .classification(outcome.wasFastTrack()
                        ? Classification.FAST_TRACK
                        : analysis.map(LlmAnalysis::getRecommendation).orElse(null))
                // Double.valueOf and not a bare 1.0: with the primitive literal the ternary types
                // as double and unboxes the null branch, which blows up with an NPE.
                .confidence(outcome.wasFastTrack()
                        ? Double.valueOf(1.0)
                        : analysis.map(a -> a.getConfidence() != null ? a.getConfidence().doubleValue() : null)
                                .orElse(null))
                // No factors on a Fast Track: the previous run's back a different classification,
                // and showing them next to FAST_TRACK would attribute reasons that aren't its own.
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
                .build();
    }

    /**
     * @return the id of the persisted {@code case_classification} row, so cases-service can point
     *         {@code cases.classification_id} at it. That link is what ties a case to the model run
     *         its verdict was based on — the audit trail Disposición SSN 2/2023 requires.
     */
    @Transactional
    public Long recordAnalystDecision(Long caseId, AnalystDecisionRequest request) {
        // A fast tracked case has no analysis to point at, but still needs an analyst's decision
        // (decision #5) — hence the nullable FK rather than a lookup failure.
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
        // Freezes the live counter from cases.classification_attempts onto the auditable row.
        // Null when the caller doesn't know it — the column is NOT NULL, so it defaults to 0.
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

    /** Same reasoning as {@link #getStatus}: plain, not readOnly — see its javadoc. */
    @Transactional
    public String getContent() {
        List<LlmAnalysis> entries = llmAnalysisRepository.findAllByOrderByIdAsc();
        if (entries.isEmpty()) {
            return "No results yet.";
        }

        StringBuilder sb = new StringBuilder("""
                # Claims Classification Results

                | Case | Recommendation | Model | Confidence | Reasons | Latency | Analyzed |
                |------|----------------|-------|------------|---------|---------|----------|
                """);

        for (LlmAnalysis e : entries) {
            sb.append(String.format(
                    "| %s | %s | %s | %s | %s | %s | %s |%n",
                    e.getCaseId(),
                    e.getRecommendation(),
                    e.getModel(),
                    e.getConfidence() != null ? e.getConfidence().toPlainString() : "-",
                    e.getReasons().stream().map(LlmReason::getReason).collect(Collectors.joining("; ")),
                    e.getLatencyMs() != null ? e.getLatencyMs() + " ms" : "-",
                    TIMESTAMP_FORMATTER.format(e.getAnalyzedAt())
            ));
        }
        return sb.toString();
    }
}
