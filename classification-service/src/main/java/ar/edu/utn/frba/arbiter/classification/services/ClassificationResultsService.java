package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.classification.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.common.dto.ClaimResponse;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.exceptions.InvalidClassificationException;
import ar.edu.utn.frba.arbiter.classification.models.entities.ClassificationLog;
import ar.edu.utn.frba.arbiter.classification.models.entities.RiskAnalysis;
import ar.edu.utn.frba.arbiter.classification.models.repositories.ClassificationLogRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.RiskAnalysisRepository;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskScore;
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

/**
 * Owns the classification_log table: persists every classification (immutable audit trail,
 * SSN Disposición 2/2023), exposes each case's latest result for polling, and renders the
 * results table for quick lookup.
 */
@Service
@RequiredArgsConstructor
public class ClassificationResultsService {

    private static final Logger log = LoggerFactory.getLogger(ClassificationResultsService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ClassificationLogRepository logRepository;
    private final RiskAnalysisRepository riskAnalysisRepository;
    private final OllamaProperties ollamaProperties;

    @Transactional
    public void saveResult(
            Long caseId,
            ClassificationResponse response,
            ImageForensicReport forensicReport,
            long latencyMs
    ) {
        boolean fastTrack = response.deterministicFastTrack();

        ClassificationLog entry = new ClassificationLog();
        entry.setCaseId(caseId);
        entry.setSource(fastTrack ? "RULES_FAST_TRACK" : "LLM");
        entry.setModel(fastTrack ? null : ollamaProperties.model());
        entry.setPromptVersion(fastTrack ? null : ollamaProperties.promptVersion());
        entry.setClassification(response.classification());
        entry.setConfidence(BigDecimal.valueOf(response.confidence()));
        entry.setFactors(response.factors());
        entry.setForensicReport(forensicReport);
        entry.setLatencyMs(latencyMs);
        entry.setInsuredName(response.insuredName());

        logRepository.save(entry);
        saveRiskAnalysis(caseId, response.riskScore());
        log.info("[ResultsService] Classification logged for case {} ({})", caseId, entry.getSource());
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
    }

    /** Latest classification for a case; classification fields stay null until a log exists for it. */
    @Transactional(readOnly = true)
    public ClaimResponse getStatus(Long caseId) {
        Optional<ClassificationLog> entry = logRepository.findFirstByCaseIdOrderByIdDesc(caseId);
        Optional<RiskAnalysis> risk = riskAnalysisRepository.findFirstByCaseIdOrderByIdDesc(caseId);

        return ClaimResponse.builder()
                .caseId(caseId)
                .classification(entry.map(ClassificationLog::getClassification).orElse(null))
                .confidence(entry.map(l -> l.getConfidence() != null ? l.getConfidence().doubleValue() : null).orElse(null))
                .factors(entry.map(ClassificationLog::getFactors).orElse(null))
                .deterministicFastTrack(entry.map(l -> "RULES_FAST_TRACK".equals(l.getSource())).orElse(false))
                .forensicReport(entry.map(ClassificationLog::getForensicReport).orElse(null))
                .riskScore(risk.map(r -> r.getRiskScore().doubleValue()).orElse(null))
                .riskBand(risk.map(RiskAnalysis::getRiskBand).orElse(null))
                .riskBreakdown(risk.map(RiskAnalysis::getRiskBreakdown).orElse(null))
                .insuredName(entry.map(ClassificationLog::getInsuredName).orElse(null))
                .build();
    }

    @Transactional
    public void recordAnalystDecision(Long caseId, AnalystDecisionRequest request) {
        ClassificationLog classificationEntry = logRepository.findFirstByCaseIdOrderByIdDesc(caseId)
                .orElseThrow(() -> new InvalidClassificationException(
                        "No classification found for case " + caseId));

        // The decision row is the latest for the case, so getStatus reads IT — it must carry a
        // faithful copy of the classification snapshot, or the analyst's poll would see the
        // factors/name vanish once a decision is recorded. Risk score isn't copied here: getStatus
        // looks up the latest RiskAnalysis by caseId independently, it doesn't ride on this row.
        ClassificationLog decisionEntry = new ClassificationLog();
        decisionEntry.setCaseId(caseId);
        decisionEntry.setSource("ANALYST");
        decisionEntry.setClassification(classificationEntry.getClassification());
        decisionEntry.setConfidence(classificationEntry.getConfidence());
        decisionEntry.setFactors(classificationEntry.getFactors());
        decisionEntry.setForensicReport(classificationEntry.getForensicReport());
        decisionEntry.setInsuredName(classificationEntry.getInsuredName());
        decisionEntry.setAnalystId(request.analystId());
        decisionEntry.setDecision(normalizeDecision(request.decision()));
        decisionEntry.setDecisionTimestamp(Instant.now());

        logRepository.save(decisionEntry);
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
        List<ClassificationLog> entries = logRepository.findAllByOrderByIdAsc();
        if (entries.isEmpty()) {
            return "No results yet.";
        }

        StringBuilder sb = new StringBuilder("""
                # Claims Classification Results

                | Case | Classification | Source | Confidence | Factors | Latency | Created |
                |------|----------------|--------|------------|---------|---------|---------|
                """);

        for (ClassificationLog e : entries) {
            sb.append(String.format(
                    "| %s | %s | %s | %s | %s | %s | %s |%n",
                    e.getCaseId() != null ? e.getCaseId().toString() : "isolated test",
                    e.getClassification(),
                    e.getSource(),
                    e.getConfidence() != null ? e.getConfidence().toPlainString() : "-",
                    String.join("; ", e.getFactors()),
                    e.getLatencyMs() != null ? e.getLatencyMs() + " ms" : "-",
                    TIMESTAMP_FORMATTER.format(e.getCreatedAt())
            ));
        }
        return sb.toString();
    }
}
