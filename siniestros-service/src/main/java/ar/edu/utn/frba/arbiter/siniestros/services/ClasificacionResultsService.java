package ar.edu.utn.frba.arbiter.siniestros.services;

import ar.edu.utn.frba.arbiter.siniestros.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.siniestros.models.entities.ClasificacionLog;
import ar.edu.utn.frba.arbiter.siniestros.models.repositories.ClasificacionLogRepository;
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
 * Persiste cada clasificación en clasificacion_log (auditoría inmutable, Disp. 2/2023)
 * y expone la tabla de resultados para consulta rápida.
 */
@Service
@RequiredArgsConstructor
public class ClasificacionResultsService {

    private static final Logger log = LoggerFactory.getLogger(ClasificacionResultsService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ClasificacionLogRepository logRepository;
    private final OllamaProperties ollamaProperties;

    @Transactional
    public void saveResult(
            Long siniestroId,
            ClassificationResponse response,
            long latencyMs
    ) {
        boolean fastTrack = response.deterministicFastTrack();

        ClasificacionLog entry = new ClasificacionLog();
        entry.setSiniestroId(siniestroId);
        entry.setSource(fastTrack ? "RULES_FAST_TRACK" : "LLM");
        entry.setModelo(fastTrack ? null : ollamaProperties.model());
        entry.setPromptVersion(fastTrack ? null : ollamaProperties.promptVersion());
        entry.setClassification(response.classification());
        entry.setConfidence(BigDecimal.valueOf(response.confidence()));
        entry.setFactores(String.join("\n", response.factors()));
        entry.setLatenciaMs(latencyMs);

        logRepository.save(entry);
        log.info("[ResultsService] Classification logged for claim {} ({})", siniestroId, entry.getSource());
    }

    @Transactional(readOnly = true)
    public String getContent() {
        List<ClasificacionLog> entries = logRepository.findAllByOrderByIdAsc();
        if (entries.isEmpty()) {
            return "No results yet.";
        }

        StringBuilder sb = new StringBuilder("""
                # Claims Classification Results

                | Claim | Classification | Source | Confidence | Factors | Latency | Created |
                |-------|----------------|--------|------------|---------|---------|---------|
                """);

        for (ClasificacionLog e : entries) {
            sb.append(String.format(
                    "| %s | %s | %s | %s | %s | %s | %s |%n",
                    e.getSiniestroId() != null ? e.getSiniestroId().toString() : "isolated test",
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
