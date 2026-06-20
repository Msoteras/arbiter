package ar.edu.utn.frba.arbiter.siniestros.services;

import ar.edu.utn.frba.arbiter.common.enums.Clasificacion;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ResultadosClasificacionService {

    private static final Logger log = LoggerFactory.getLogger(ResultadosClasificacionService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${arbiter.resultados.path}")
    private String resultsPath;

    public void saveResult(
            Long claimId,
            String policyNumber,
            String insuredId,
            Clasificacion classification,
            Double confidence,
            List<String> factors,
            Long latencyMs
    ) {
        try {
            Path path = Paths.get(resultsPath);

            if (!Files.exists(path)) {
                createFileWithHeader(path);
            }

            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            String factorsStr = String.join(" | ", factors);
            String row = String.format(
                    "| %d | %s | %s | CLASSIFIED | %s | %.2f | %d ms | %s |\n",
                    claimId,
                    policyNumber,
                    insuredId,
                    classification,
                    confidence,
                    latencyMs,
                    timestamp
            );

            Files.write(path, row.getBytes(), StandardOpenOption.APPEND);
            log.info("[ResultsService] Result saved for claim {} at {}", claimId, resultsPath);

        } catch (IOException e) {
            log.error("[ResultsService] Error saving result for claim {}", claimId, e);
        }
    }

    private void createFileWithHeader(Path path) throws IOException {
        String header = """
                # Claims Classification Results

                Generated automatically by the analysis and classification module.

                | ID | Policy | Insured ID | Status | Classification | Confidence | Latency | Timestamp |
                |----|--------|------------|--------|----------------|------------|---------|-----------|
                """;

        Files.createDirectories(path.getParent());
        Files.write(path, header.getBytes());
    }

    public String getContent() {
        try {
            Path path = Paths.get(resultsPath);
            if (Files.exists(path)) {
                return Files.readString(path);
            }
            return "No results yet.";
        } catch (IOException e) {
            log.error("Error reading results file", e);
            return "Error reading results.";
        }
    }
}
