package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.config.OllamaProperties;
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

/**
 * Persists every classification into classification_log (immutable audit trail, SSN
 * Disposición 2/2023) and exposes the results table for quick lookup.
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
            Long claimId,
            ClassificationResponse response,
            long latencyMs
    ) {
        boolean fastTrack = response.deterministicFastTrack();

        ClassificationLog entry = new ClassificationLog();
        entry.setClaimId(claimId);
        entry.setSource(fastTrack ? "RULES_FAST_TRACK" : "LLM");
        entry.setModelo(fastTrack ? null : ollamaProperties.model());
        entry.setPromptVersion(fastTrack ? null : ollamaProperties.promptVersion());
        entry.setClassification(response.classification());
        entry.setConfidence(BigDecimal.valueOf(response.confidence()));
        entry.setFactores(String.join("\n", response.factors()));
        entry.setLatenciaMs(latencyMs);

        logRepository.save(entry);
        log.info("[ResultsService] Classification logged for claim {} ({})", claimId, entry.getSource());
    }

    @Transactional(readOnly = true)
    public String getContent() {
        List<ClassificationLog> entries = logRepository.findAllByOrderByIdAsc();
        if (entries.isEmpty()) {
            return "No results yet.";
        }

        StringBuilder sb = new StringBuilder("""
                # Claims Classification Results

                | Claim | Classification | Source | Confidence | Factors | Latency | Created |
                |-------|----------------|--------|------------|---------|---------|---------|
                """);

        for (ClassificationLog e : entries) {
            sb.append(String.format(
                    "| %s | %s | %s | %s | %s | %s | %s |%n",
                    e.getClaimId() != null ? e.getClaimId().toString() : "isolated test",
                    e.getClassification(),
                    e.getSource(),
                    e.getConfidence() != null ? e.getConfidence().toPlainString() : "-",
                    e.getFactores().replace("\n", "; "),
                    e.getLatenciaMs() != null ? e.getLatenciaMs() + " ms" : "-",
                    TIMESTAMP_FORMATTER.format(e.getCreatedAt())
            ));
        }
        return sb.toString();
    }
}
