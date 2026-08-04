package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.ClipClient;
import ar.edu.utn.frba.arbiter.classification.config.EmbeddingProperties;
import ar.edu.utn.frba.arbiter.classification.dto.DuplicateImageMatch;
import ar.edu.utn.frba.arbiter.classification.dto.ImageAnalysisOutcome;
import ar.edu.utn.frba.arbiter.classification.models.entities.ImageAnalysis;
import ar.edu.utn.frba.arbiter.classification.models.repositories.ImageAnalysisRepository;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport.WebFinding;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ImageEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(ImageEmbeddingService.class);

    private static final String MATCH_TYPE_INTERNAL = "INTERNAL_DUPLICATE";
    private static final String MATCH_TYPE_WEB_FULL = "WEB_FULL";
    private static final String MATCH_TYPE_WEB_PARTIAL = "WEB_PARTIAL";
    private static final String EXTERNAL_SOURCE_WEB = "google-vision";

    private final ClipClient clipClient;
    private final ImageAnalysisRepository repository;
    private final EmbeddingProperties properties;

    /**
     * Embeds the image, records the analysis against its document, and reports images from
     * other cases that look the same.
     *
     * <p>The similarity search runs <em>before</em> the row is saved, so this image can never
     * match itself.
     *
     * @param caseId         the case being classified — excluded from the search, so an image
     *                       never matches its own siblings either
     * @param caseDocumentId the {@code case_documents} row this image came from. Null for the
     *                       isolated-classification endpoint, where there is nothing to anchor
     *                       to: the image is still compared, but no analysis is persisted.
     */
    @Transactional
    public ImageAnalysisOutcome processAndFindDuplicates(
            Long caseId,
            Long caseDocumentId,
            String attachmentLabel,
            String imageBase64
    ) {
        if (!properties.enabled()) {
            log.debug("[ImageEmbedding] Disabled — skipping duplicate check");
            return ImageAnalysisOutcome.none();
        }

        log.info("[ImageEmbedding] Generating embedding for case={} attachment='{}'", caseId, attachmentLabel);
        float[] vector = clipClient.embed(imageBase64);
        log.info("[ImageEmbedding] Embedding generated — {} dimensions", vector.length);

        String vectorLiteral = toVectorLiteral(vector);

        List<DuplicateImageMatch> matches = repository.findSimilar(
                        vectorLiteral, caseId, properties.similarityThreshold(), properties.maxResults())
                .stream()
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

        if (caseDocumentId == null) {
            log.debug("[ImageEmbedding] No document id for case={} — compared without persisting", caseId);
            return new ImageAnalysisOutcome(null, matches);
        }

        Long analysisId = persist(caseDocumentId, vectorLiteral, matches);
        return new ImageAnalysisOutcome(analysisId, matches);
    }

    /** Stores what the external web search found, on the row {@link #processAndFindDuplicates} wrote. */
    @Transactional
    public void recordWebMatch(Long analysisId, WebFinding finding) {
        if (analysisId == null || finding == null || !finding.found()) {
            return;
        }
        String reference = finding.pages().isEmpty() ? finding.bestGuessLabel() : finding.pages().getFirst().url();
        String matchType = finding.fullMatches() > 0 ? MATCH_TYPE_WEB_FULL : MATCH_TYPE_WEB_PARTIAL;
        repository.recordWebMatch(analysisId, EXTERNAL_SOURCE_WEB, reference, matchType);
    }

    /**
     * Saves through JPA first to get the id back, then sets the vector natively — Hibernate has
     * no type for a pgvector column, so it can't be part of the mapped insert.
     */
    private Long persist(Long caseDocumentId, String vectorLiteral, List<DuplicateImageMatch> matches) {
        ImageAnalysis analysis = new ImageAnalysis();
        analysis.setCaseDocumentId(caseDocumentId);
        analysis.setModel(properties.model());
        analysis.setAnalyzedAt(Instant.now());

        // The best match is the first: findSimilar orders by similarity descending.
        if (!matches.isEmpty()) {
            DuplicateImageMatch best = matches.getFirst();
            analysis.setSimilarDocumentId(best.matchedDocumentId());
            analysis.setSimilarityScore(BigDecimal.valueOf(best.similarity()));
            analysis.setMatchType(MATCH_TYPE_INTERNAL);
            analysis.setSuspicious(true);
        }

        ImageAnalysis saved = repository.save(analysis);
        repository.setEmbedding(saved.getId(), vectorLiteral);
        return saved.getId();
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
        Long matchedDocumentId = row[1] != null ? ((Number) row[1]).longValue() : null;
        String matchedAttachmentLabel = (String) row[2];
        String matchedFilename = (String) row[3];
        double similarity = ((Number) row[4]).doubleValue();
        return new DuplicateImageMatch(
                matchedCaseId, matchedDocumentId, matchedAttachmentLabel, matchedFilename, similarity);
    }
}
