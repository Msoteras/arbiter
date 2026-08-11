package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.config.OllamaProperties;
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
    private final OllamaProperties ollamaProperties;

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
            analysis.setModel(ollamaProperties.model());
            analysis.setPromptVersion(ollamaProperties.promptVersion());
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
     * Audita las reglas duras evaluadas (hoy, exclusiones de cobertura) en {@code rule_result} —
     * cierra el D4c: la tabla existía con entidad y repository pero cero escritores. Se escribe tanto
     * el PASS como el FAIL: la auditoría de la Disposición SSN 2/2023 es "qué regla se evaluó y con
     * qué resultado", no solo los rechazos. {@code rule_id} es FK NOT NULL a {@code insurer_rule}, y
     * viaja desde la regla evaluada. Solo en el flujo con {@code caseId}: la fila referencia
     * {@code cases(id)}, así que el flujo aislado (sin expediente) no escribe.
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

        // Con qué configuración se calculó (D29). Null cuando el scoring salió del baseline y no de
        // una fila del referente: ahí no hay nada que apuntar, y la FK lo rechazaría.
        if (riskScore.scoringConfigurationId() != null) {
            caseOutcomeRepository.saveScoringConfiguration(caseId, riskScore.scoringConfigurationId());
        }
    }

    /** Latest classification for a case; classification fields stay null until one exists. */
    @Transactional(readOnly = true)
    public ClaimResponse getStatus(Long caseId) {
        Optional<LlmAnalysis> analysis = llmAnalysisRepository.findFirstByCaseIdOrderByIdDesc(caseId);
        Optional<RiskAnalysis> risk = riskAnalysisRepository.findFirstByCaseIdOrderByIdDesc(caseId);
        CaseOutcomeRepository.CaseOutcome outcome = caseOutcomeRepository.findOutcome(caseId);

        return ClaimResponse.builder()
                .caseId(caseId)
                // El Fast Track manda cuando está: un Fast Track no deja fila en llm_analysis, y
                // esa tabla es append-only, así que si se preguntara primero por ella ganaría la
                // corrida ANTERIOR. Pasa al reclasificar (subir la documentación que faltaba y
                // que el gate resuelva Fast Track): quedaba mostrándose el FALTA_DOCUMENTACION
                // viejo. El flag no tiene ese problema porque se reescribe en cada corrida, así
                // que en true significa siempre "la última fue Fast Track".
                .classification(outcome.wasFastTrack()
                        ? Classification.FAST_TRACK
                        : analysis.map(LlmAnalysis::getRecommendation).orElse(null))
                // Double.valueOf y no 1.0 a secas: con el literal primitivo, el ternario se tipa
                // como double y desempaqueta la rama nula, que revienta con NPE.
                .confidence(outcome.wasFastTrack()
                        ? Double.valueOf(1.0)
                        : analysis.map(a -> a.getConfidence() != null ? a.getConfidence().doubleValue() : null)
                                .orElse(null))
                // Sin motivos en un Fast Track: los de la corrida anterior fundamentan otra
                // clasificación, mostrarlos al lado de FAST_TRACK sería atribuirle razones que no
                // son suyas.
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

    @Transactional(readOnly = true)
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
