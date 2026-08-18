package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.exceptions.InvalidClassificationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Reads an attached document with Qwen3-VL's vision capability: what it says (OCR, ready to inject
 * into the classification prompt) and what it looks like (signs of manipulation). PDFs are
 * rasterized page by page first — the model only accepts images. HTTP is delegated to
 * {@link OllamaClient}.
 *
 * <p>This is the <b>only</b> pass that has the image in front of it. The classifier itself works on
 * text: sending it the images would spend thousands of tokens of the 32k window (decision #2) on
 * something a vision model already looked at, and image reuse is covered by CLIP/pgvector, not by
 * the model (decision #11). So whatever has to be noticed <i>visually</i> has to be noticed here —
 * that's what {@code visualFindings} is for (D5).
 */
@Service
public class OllamaDocumentAnalyzer implements DocumentAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(OllamaDocumentAnalyzer.class);

    /** Qwen3-VL via Ollama only accepts images (jpg/png/webp/...), not PDF — needs rasterizing first. */
    private static final int MAX_PDF_PAGES = 5;

    private static final String UNREADABLE = "No se pudo extraer contenido del documento adjunto.";

    /**
     * Forcing the shape is what keeps the two halves apart. Without it the model returns prose and
     * an "observación:" line inside the transcription reads as if the document said it.
     */
    private static final Map<String, Object> OUTPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "transcription", Map.of("type", "string"),
                    "visualFindings", Map.of("type", "array", "items", Map.of("type", "string")),
                    // All nullable: a document has no reason to carry all four. The schema doesn't
                    // require them so the model doesn't invent what's missing.
                    "fields", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "documentDate", Map.of("type", List.of("string", "null")),
                                    "amount", Map.of("type", List.of("number", "null")),
                                    "itemDescription", Map.of("type", List.of("string", "null")),
                                    "imei", Map.of("type", List.of("string", "null")),
                                    "affectedParty", Map.of("enum",
                                            List.of("TITULAR", "FAMILIAR", "TERCERO", "DESCONOCIDO"))
                            ))
            ),
            "required", List.of("transcription", "visualFindings")
    );

    private final OllamaClient client;
    private final ObjectMapper objectMapper;
    private final String documentExtractionPrompt;

    public OllamaDocumentAnalyzer(
            OllamaClient client,
            ObjectMapper objectMapper,
            @Value("classpath:prompts/extraccion-documento-v3.md") Resource documentExtractionPromptResource
    ) throws IOException {
        this.client = client;
        this.objectMapper = objectMapper;
        this.documentExtractionPrompt = documentExtractionPromptResource.getContentAsString(StandardCharsets.UTF_8);
    }

    @Override
    public DocumentExtraction extract(byte[] content, String contentType) {
        log.info("[Ollama] Starting document analysis — model={} contentType={} sizeBytes={} magicBytes={} decodableByJava={}",
                client.model(), contentType, content.length, magicBytesHex(content), isDecodableImage(content));

        if (isPdf(contentType, content)) {
            return extractFromPdf(content);
        }
        return extractFromImage(content);
    }

    private boolean isPdf(String contentType, byte[] content) {
        if ("application/pdf".equalsIgnoreCase(contentType)) {
            return true;
        }
        return content.length >= 4
                && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F';
    }

    /** Diagnostic: first bytes (format signature) to identify what actually arrived. */
    private String magicBytesHex(byte[] content) {
        int len = Math.min(content.length, 16);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X ", content[i]));
        }
        return sb.toString().trim();
    }

    /** Diagnostic: if even Java's own decoder can't read it, it's a format problem (e.g. HEIC), not Ollama's fault. */
    private boolean isDecodableImage(byte[] content) {
        try {
            return ImageIO.read(new ByteArrayInputStream(content)) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Page transcriptions are concatenated with their heading; findings are pooled across pages,
     * prefixed with the page when there's more than one — a doctored stamp on page 3 is useless
     * information if the analyst can't tell which page to open.
     */
    private DocumentExtraction extractFromPdf(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = Math.min(document.getNumberOfPages(), MAX_PDF_PAGES);
            if (document.getNumberOfPages() > MAX_PDF_PAGES) {
                log.warn("[Ollama] PDF has {} pages, only analyzing the first {}",
                        document.getNumberOfPages(), MAX_PDF_PAGES);
            }

            log.info("[Ollama] PDF has {} page(s) to read", pageCount);
            StringBuilder transcription = new StringBuilder();
            List<String> findings = new ArrayList<>();
            DocumentExtraction.Fields fields = DocumentExtraction.Fields.none();
            for (int page = 0; page < pageCount; page++) {
                log.info("[Ollama] Rendering and reading page {}/{}...", page + 1, pageCount);
                BufferedImage image = renderer.renderImageWithDPI(page, 150);
                DocumentExtraction pageExtraction = extractFromImage(toPng(image));

                if (pageCount > 1) {
                    transcription.append("--- Página ").append(page + 1).append(" ---\n");
                }
                transcription.append(pageExtraction.transcription()).append("\n");

                int pageNumber = page + 1;
                pageExtraction.visualFindings().forEach(finding -> findings.add(
                        pageCount > 1 ? "Página " + pageNumber + ": " + finding : finding));

                fields = mergeFields(fields, pageExtraction.fields());
            }
            log.info("[Ollama] PDF fully read — {} page(s)", pageCount);
            return new DocumentExtraction(transcription.toString().trim(), findings, fields);
        } catch (IOException e) {
            throw new InvalidClassificationException("Could not render PDF document for analysis", e);
        }
    }

    /**
     * A PDF is one document, not several: its fields are the set's. The first page carrying each
     * value wins — the IMEI is usually on the first and the total on the last, so keeping only one
     * would lose half.
     */
    private DocumentExtraction.Fields mergeFields(
            DocumentExtraction.Fields accumulated, DocumentExtraction.Fields page) {
        return new DocumentExtraction.Fields(
                accumulated.documentDate() != null ? accumulated.documentDate() : page.documentDate(),
                accumulated.amount() != null ? accumulated.amount() : page.amount(),
                accumulated.itemDescription() != null ? accumulated.itemDescription() : page.itemDescription(),
                accumulated.imei() != null ? accumulated.imei() : page.imei(),
                accumulated.affectedParty() != null ? accumulated.affectedParty() : page.affectedParty());
    }

    private byte[] toPng(BufferedImage image) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new InvalidClassificationException("Could not encode rendered PDF page as image", e);
        }
    }

    private DocumentExtraction extractFromImage(byte[] imageContent) {
        String base64 = Base64.getEncoder().encodeToString(imageContent);

        // Sin thinking: transcribir un documento es una tarea mecánica —leer lo que dice el papel—,
        // no una que se resuelva razonando. El schema ya fuerza la forma de la salida, así que el
        // razonamiento solo agrega minutos y consume el presupuesto de num_predict sin aportar.
        String content = client.chat(documentExtractionPrompt, List.of(base64), OUTPUT_SCHEMA, false);

        if (content.isEmpty()) {
            log.warn("[Ollama] Document analysis returned empty content");
            return DocumentExtraction.of(UNREADABLE);
        }

        DocumentExtraction extraction = parse(content);
        log.info("[Ollama] Document analysis done — {} chars transcribed, {} visual finding(s)",
                extraction.transcription().length(), extraction.visualFindings().size());
        log.debug("[Ollama] Extraction:\n{}", extraction);
        return extraction;
    }

    /**
     * A document that couldn't be parsed degrades to its raw text with no findings, instead of
     * failing the classification: the transcription is the part the flow actually depends on, and
     * a malformed answer is not evidence of anything visual. Silence beats a made-up finding.
     */
    private DocumentExtraction parse(String contentJson) {
        try {
            ModelOutput output = objectMapper.readValue(contentJson, ModelOutput.class);
            String transcription = output.transcription() == null || output.transcription().isBlank()
                    ? UNREADABLE
                    : output.transcription();
            return new DocumentExtraction(transcription, output.visualFindings(), toFields(output.fields()));
        } catch (Exception e) {
            log.warn("[Ollama] Could not parse document extraction, keeping the raw text: {}", e.getMessage());
            return DocumentExtraction.of(contentJson);
        }
    }

    /**
     * A field that can't be interpreted stays null, it doesn't break the extraction: the rest of
     * the document is still useful. And null is never read as an inconsistency downstream — "the
     * document doesn't say" and "doesn't match" are different things.
     */
    private DocumentExtraction.Fields toFields(ModelFields fields) {
        if (fields == null) {
            return DocumentExtraction.Fields.none();
        }
        return new DocumentExtraction.Fields(
                parseDate(fields.documentDate()),
                fields.amount(),
                blankToNull(fields.itemDescription()),
                normalizeImei(fields.imei()),
                parseAffectedParty(fields.affectedParty()));
    }

    /** A value outside the enum is treated as "the document doesn't say", not as an error. */
    private DocumentExtraction.AffectedParty parseAffectedParty(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DocumentExtraction.AffectedParty.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.debug("[Ollama] Unknown affectedParty '{}' — left empty", raw);
            return null;
        }
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            log.debug("[Ollama] Unparseable document date '{}' — left empty", raw);
            return null;
        }
    }

    /** Digits only: the model may return it with spaces or dashes and that's no real difference. */
    private String normalizeImei(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }

    private String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private record ModelOutput(String transcription, List<String> visualFindings, ModelFields fields) {}

    /** The date arrives as text and the IMEI may carry separators: both normalized on mapping. */
    private record ModelFields(String documentDate, BigDecimal amount, String itemDescription, String imei,
                               String affectedParty) {}
}
