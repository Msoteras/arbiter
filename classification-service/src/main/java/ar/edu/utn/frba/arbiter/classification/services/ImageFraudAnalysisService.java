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
 * Escalating cascade: internal CLIP/pgvector comparison first; the web search (third party, costs
 * money, image leaves our infrastructure) only for images with no internal match. Failures never
 * break the classification.
 */
@Service
@RequiredArgsConstructor
public class ImageFraudAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ImageFraudAnalysisService.class);

    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp");

    private final ImageEmbeddingService imageEmbeddingService;
    private final GoogleVisionClient googleVisionClient;

    public ImageForensicReport analyze(Long caseId, List<AttachmentDocument> documents, boolean imageConsent) {
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
                webFinding = findOnWeb(label, imageBase64, imageConsent);
                if (webFinding != null) {
                    webSearchesPerformed++;
                    // Persisted so it's queryable, not only inside the report JSON.
                    imageEmbeddingService.recordWebMatch(outcome.analysisId(), webFinding);
                }
            }

            findings.add(new ImageFinding(label, doc.type(), internalMatches, webFinding, outcome.comparisonFailed()));
        }

        log.info("[ImageFraud] caseId={} images={} webSearches={} consent={}",
                caseId, imagesAnalyzed, webSearchesPerformed, imageConsent);
        return new ImageForensicReport(
                imagesAnalyzed, webSearchesPerformed, imageConsent, List.copyOf(findings));
    }

    /**
     * One factor per finding, as "<document>: <what was found>." The document goes by its code: the
     * front shows its name and links it.
     */
    public List<String> renderTraces(ImageForensicReport report) {
        List<String> traces = new ArrayList<>();
        for (ImageFinding f : report.findings()) {
            if (!f.internalMatches().isEmpty()) {
                f.internalMatches().forEach(m -> traces.add(String.format(
                        "%s: %.0f%% similar a un adjunto del expediente EXP-%d.",
                        f.documentType(), m.similarity() * 100, m.matchedCaseId())));
                continue;
            }
            traces.add(String.format(Boolean.TRUE.equals(f.internalCheckFailed())
                    ? "%s: no se pudo comparar con adjuntos de siniestros previos."
                    : "%s: no coincide con adjuntos de siniestros previos.", f.documentType()));

            if (f.webFinding() == null) {
                // Only an explicit refusal is explained ("not allowed to look" vs "no evidence");
                // null means the report predates the field.
                if (Boolean.FALSE.equals(report.imageConsent())) {
                    traces.add(String.format(
                            "%s: no se buscó en internet porque el asegurado no dio su consentimiento.",
                            f.documentType()));
                }
                continue;
            }
            if (!f.webFinding().found()) {
                traces.add(String.format("%s: no aparece en internet.", f.documentType()));
            } else {
                String guess = f.webFinding().bestGuessLabel();
                traces.add(String.format("%s: aparece en internet, %s.%s",
                        f.documentType(), webMatches(f.webFinding()),
                        guess == null || guess.isBlank() ? "" : " Se identificó como " + guess + "."));
            }
        }
        return traces;
    }

    /** "4 copias exactas y 2 parciales en 10 páginas", for the factors and the score's rationale. */
    public static String webMatches(WebFinding web) {
        return String.format("con %d %s y %d %s en %d %s",
                web.fullMatches(), web.fullMatches() == 1 ? "copia exacta" : "copias exactas",
                web.partialMatches(), web.partialMatches() == 1 ? "parcial" : "parciales",
                web.pages().size(), web.pages().size() == 1 ? "página" : "páginas");
    }

    private ImageAnalysisOutcome analyseInternally(
            Long caseId, Long caseDocumentId, String label, String imageBase64) {
        try {
            return imageEmbeddingService.processAndFindDuplicates(caseId, caseDocumentId, label, imageBase64);
        } catch (Exception e) {
            log.warn("[ImageFraud] Internal check failed for '{}' — {}", label, e.getMessage());
            return ImageAnalysisOutcome.failed();
        }
    }

    /** Null when the search wasn't performed (disabled, no consent, or failed). */
    private WebFinding findOnWeb(String label, String imageBase64, boolean imageConsent) {
        if (!imageConsent) {
            log.debug("[ImageFraud] Web search skipped for '{}' — insured did not consent", label);
            return null;
        }
        if (!googleVisionClient.isEnabled()) {
            return null;
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
        return new InternalMatch(
                m.matchedCaseId(), m.matchedAttachmentLabel(), m.matchedFilename(), m.similarity());
    }
}
