package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.common.dto.ClaimResponse;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.models.entities.ClassificationLog;
import ar.edu.utn.frba.arbiter.classification.models.repositories.ClassificationLogRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final OllamaProperties ollamaProperties;

    @Transactional
    public void saveResult(
            Long caseId,
            ClassificationResponse response,
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
        entry.setLatencyMs(latencyMs);

        logRepository.save(entry);
        log.info("[ResultsService] Classification logged for case {} ({})", caseId, entry.getSource());
    }

    /** Latest classification for a case; classification fields stay null until a log exists for it. */
    @Transactional(readOnly = true)
    public ClaimResponse getStatus(Long caseId) {
        Optional<ClassificationLog> entry = logRepository.findFirstByCaseIdOrderByIdDesc(caseId);

        return ClaimResponse.builder()
                .caseId(caseId)
                .classification(entry.map(ClassificationLog::getClassification).orElse(null))
                .confidence(entry.map(l -> l.getConfidence() != null ? l.getConfidence().doubleValue() : null).orElse(null))
                .factors(entry.map(ClassificationLog::getFactors).orElse(null))
                .deterministicFastTrack(entry.map(l -> "RULES_FAST_TRACK".equals(l.getSource())).orElse(false))
                .build();
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
