package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.GoogleVisionClient;
import ar.edu.utn.frba.arbiter.classification.dto.AttachmentDocument;
import ar.edu.utn.frba.arbiter.classification.dto.DuplicateImageMatch;
import ar.edu.utn.frba.arbiter.classification.dto.ImageAnalysisOutcome;
import ar.edu.utn.frba.arbiter.classification.dto.WebImageMatch;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport.ImageFinding;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport.InternalMatch;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport.WebFinding;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Image-fraud analysis, run as part of analyzing the claim's documentation (images are just
 * another attachment): the orchestrator invokes it exactly when the documentation is examined —
 * Fast Track with a required document included, structured-data-only Fast Track excluded. Runs as
 * an escalating cascade:
 *
 * <ol>
 *   <li><b>Internal first</b> — compare the image against attachments of previous claims using
 *       our own CLIP + pgvector index. Free, private, and it never leaves the host.</li>
 *   <li><b>Escalate only if needed</b> — an image with no internal match is the one worth
 *       looking up on the web. Reaching a third party costs money and takes the insured's
 *       image outside our infrastructure, so it's the fallback, never the first move.</li>
 * </ol>
 *
 * <p>An image that already matched a previous claim needs no web search: the finding is
 * established. That's what keeps external calls (and data exposure) down to the minimum.
 *
 * <p>Produces a structured {@link ImageForensicReport} (grouped per image, for the analyst UI).
 * Failures degrade to an empty finding and never break the classification.
 */
@Service
@RequiredArgsConstructor
public class ImageFraudAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ImageFraudAnalysisService.class);

    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp");

    private final ImageEmbeddingService imageEmbeddingService;
    private final GoogleVisionClient googleVisionClient;

    public ImageForensicReport analyze(Long caseId, List<AttachmentDocument> documents) {
        List<ImageFinding> findings = new ArrayList<>();
        int imagesAnalyzed = 0;
        int webSearchesPerformed = 0;

        for (AttachmentDocument doc : documents) {
            if (!IMAGE_CONTENT_TYPES.contains(doc.contentType())) {
                continue;
            }

            String label = doc.type() + "-" + imagesAnalyzed;
            String imageBase64 = Base64.getEncoder().encodeToString(doc.content());
            imagesAnalyzed++;

            ImageAnalysisOutcome outcome =
                    analyseInternally(caseId, doc.documentId(), label, imageBase64);
            List<InternalMatch> internalMatches = outcome.duplicates().stream()
                    .map(this::toInternalMatch)
                    .toList();

            WebFinding webFinding = null;
            if (internalMatches.isEmpty()) {
                webFinding = findOnWeb(label, imageBase64); // escalate only when no internal match
                if (webFinding != null) {
                    webSearchesPerformed++;
                    // Persist it on the row the internal pass wrote, so "suspicious because it's
                    // published on the web" is queryable and not only inside the report JSON.
                    imageEmbeddingService.recordWebMatch(outcome.analysisId(), webFinding);
                }
            }

            findings.add(new ImageFinding(label, doc.type(), internalMatches, webFinding));
        }

        log.info("[ImageFraud] caseId={} images={} webSearches={}", caseId, imagesAnalyzed, webSearchesPerformed);
        return new ImageForensicReport(imagesAnalyzed, webSearchesPerformed, List.copyOf(findings));
    }

    /**
     * Renders the report as human-readable Spanish traces for the classification factors and the
     * audit log. Kept alongside the structured report so both come from the same source of truth.
     */
    public List<String> renderTraces(ImageForensicReport report) {
        List<String> traces = new ArrayList<>();
        for (ImageFinding f : report.findings()) {
            if (!f.internalMatches().isEmpty()) {
                f.internalMatches().forEach(m -> traces.add(String.format(
                        "⚠ Imagen '%s': %.0f%% similar a un adjunto del siniestro #%d ('%s')",
                        f.filename(), m.similarity() * 100, m.matchedCaseId(), m.matchedFilename())));
                continue;
            }
            traces.add(String.format("Imagen '%s': sin coincidencias con adjuntos de siniestros previos", f.filename()));

            if (f.webFinding() == null) {
                continue; // web not searched (disabled or failed) — nothing more to say for this image
            }
            if (!f.webFinding().found()) {
                traces.add(String.format("Imagen '%s': tampoco se encontró publicada en internet", f.filename()));
            } else {
                String where = f.webFinding().pages().stream()
                        .limit(3).map(WebFinding.Page::url)
                        .reduce((a, b) -> a + ", " + b).orElse("");
                traces.add(String.format(
                        "⚠ Imagen '%s': publicada en internet — %d exacta(s), %d parcial(es), %d página(s). "
                                + "Identificada como '%s'. Ej.: %s",
                        f.filename(), f.webFinding().fullMatches(), f.webFinding().partialMatches(),
                        f.webFinding().pages().size(), f.webFinding().bestGuessLabel(), where));
            }
        }
        return traces;
    }

    private ImageAnalysisOutcome analyseInternally(
            Long caseId, Long caseDocumentId, String label, String imageBase64) {
        try {
            return imageEmbeddingService.processAndFindDuplicates(caseId, caseDocumentId, label, imageBase64);
        } catch (Exception e) {
            log.warn("[ImageFraud] Internal check failed for '{}' — {}", label, e.getMessage());
            return ImageAnalysisOutcome.none();
        }
    }

    /** @return the web finding, or null when the search wasn't performed (disabled or failed). */
    private WebFinding findOnWeb(String label, String imageBase64) {
        if (!googleVisionClient.isEnabled()) {
            return null; // not searched — the UI must not read this as "found nothing"
        }
        try {
            WebImageMatch match = googleVisionClient.detectWebMatches(imageBase64);
            List<WebFinding.Page> pages = match.pages().stream()
                    .map(p -> new WebFinding.Page(p.url(), p.title()))
                    .toList();
            return new WebFinding(match.fullMatches(), match.partialMatches(), pages, match.bestGuessLabel());
        } catch (Exception e) {
            log.warn("[ImageFraud] Web check failed for '{}' — {}", label, e.getMessage());
            return null;
        }
    }

    private InternalMatch toInternalMatch(DuplicateImageMatch m) {
        return new InternalMatch(m.matchedCaseId(), m.matchedFilename(), m.similarity());
    }
}
