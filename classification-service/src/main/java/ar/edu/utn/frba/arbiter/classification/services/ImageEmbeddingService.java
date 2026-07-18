package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.ClipClient;
import ar.edu.utn.frba.arbiter.classification.config.EmbeddingProperties;
import ar.edu.utn.frba.arbiter.classification.dto.DuplicateImageMatch;
import ar.edu.utn.frba.arbiter.classification.models.repositories.ImageEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ImageEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(ImageEmbeddingService.class);

    private final ClipClient clipClient;
    private final ImageEmbeddingRepository repository;
    private final EmbeddingProperties properties;

    @Transactional
    public List<DuplicateImageMatch> processAndFindDuplicates(
            Long caseId,
            String attachmentLabel,
            String originalFilename,
            String imageBase64
    ) {
        if (!properties.enabled()) {
            log.debug("[ImageEmbedding] Disabled — skipping duplicate check");
            return List.of();
        }

        log.info("[ImageEmbedding] Generating embedding for case={} attachment='{}'", caseId, attachmentLabel);
        float[] vector = clipClient.embed(imageBase64);
        log.info("[ImageEmbedding] Embedding generated — {} dimensions", vector.length);

        String vectorLiteral = toVectorLiteral(vector);

        repository.insertWithVector(caseId, attachmentLabel, originalFilename, properties.model(), vectorLiteral);

        List<Object[]> rawMatches = repository.findSimilar(
                vectorLiteral, caseId, properties.similarityThreshold(), properties.maxResults());

        List<DuplicateImageMatch> matches = rawMatches.stream()
                .map(this::toMatch)
                .toList();

        if (!matches.isEmpty()) {
            log.warn("[ImageEmbedding] Found {} duplicate(s) for case={}: {}", matches.size(), caseId,
                    matches.stream()
                            .map(m -> "case=" + m.matchedCaseId() + " sim=" + String.format("%.4f", m.similarity()))
                            .collect(Collectors.joining(", ")));
        } else {
            log.info("[ImageEmbedding] No duplicates found for case={}", caseId);
        }

        return matches;
    }

    private String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private DuplicateImageMatch toMatch(Object[] row) {
        Long matchedCaseId = row[0] != null ? ((Number) row[0]).longValue() : null;
        String matchedAttachmentId = (String) row[1];
        String matchedFilename = (String) row[2];
        double similarity = ((Number) row[3]).doubleValue();
        return new DuplicateImageMatch(matchedCaseId, matchedAttachmentId, matchedFilename, similarity);
    }
}
