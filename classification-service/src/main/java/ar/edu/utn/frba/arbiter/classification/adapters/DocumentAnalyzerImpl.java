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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an attachment with the vision model: its text (OCR) and signs of manipulation. This is the
 * only pass that sees the image (the classifier works on text), so anything visual must be captured
 * here in {@code visualFindings}.
 */
@Service
public class DocumentAnalyzerImpl implements DocumentAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(DocumentAnalyzerImpl.class);

    /** The vision model only accepts images, so PDFs are rasterized page by page. */
    private static final int MAX_PDF_PAGES = 5;

    private static final String UNREADABLE = "No se pudo extraer contenido del documento adjunto.";

    /** One retry: a cut answer usually comes back whole, and each call is billed on Gemini. */
    private static final int MAX_EXTRACTION_ATTEMPTS = 2;

    /** Mirror {@code DocumentDetail}'s columns: a longer value is trimmed, never dropped. */
    private static final int DETAIL_NAME_MAX = 100;
    private static final int DETAIL_VALUE_MAX = 500;

    private static final Pattern TRANSCRIPTION_START = Pattern.compile("\"transcription\"\\s*:\\s*\"");

    /** Where the branch's claim causes go in the prompt. */
    private static final String CATALOG_PLACEHOLDER = "{{claimCauseCatalog}}";

    private final LlmClient client;
    private final ObjectMapper objectMapper;
    private final String documentExtractionPrompt;

    public DocumentAnalyzerImpl(
            LlmClient client,
            ObjectMapper objectMapper,
            @Value("classpath:prompts/extraccion-documento-v6.md") Resource documentExtractionPromptResource
    ) throws IOException {
        this.client = client;
        this.objectMapper = objectMapper;
        this.documentExtractionPrompt = documentExtractionPromptResource.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Forcing the shape is what keeps the two halves apart. Without it the model returns prose and
     * an "observación:" line inside the transcription reads as if the document said it.
     *
     * <p>Built per call and not a constant because {@code describedClaimCause} is an enum of the
     * branch's own catalog: the model can only name a cause the insurer has, so the code never has
     * to guess which one "robo con violencia" meant. Null is one of the values — most documents
     * narrate no event at all.
     */
    private static Map<String, Object> outputSchema(List<String> claimCauses) {
        List<String> causeValues = new ArrayList<>(claimCauses);
        causeValues.add(null);
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "transcription", Map.of("type", "string"),
                        "visualFindings", Map.of("type", "array", "items", Map.of("type", "string")),
                        // All nullable: a document has no reason to carry every one of them. The
                        // schema doesn't require them so the model doesn't invent what's missing.
                        // Map.ofEntries and not Map.of: past ten pairs the varargs overload is gone.
                        "fields", Map.of(
                                "type", "object",
                                "properties", Map.ofEntries(
                                        Map.entry("documentDate", Map.of("type", List.of("string", "null"))),
                                        Map.entry("amount", Map.of("type", List.of("number", "null"))),
                                        Map.entry("itemDescription", Map.of("type", List.of("string", "null"))),
                                        Map.entry("brand", Map.of("type", List.of("string", "null"))),
                                        Map.entry("model", Map.of("type", List.of("string", "null"))),
                                        Map.entry("imei", Map.of("type", List.of("string", "null"))),
                                        Map.entry("affectedParty", Map.of("enum",
                                                List.of("TITULAR", "FAMILIAR", "TERCERO", "DESCONOCIDO"))),
                                        Map.entry("describedClaimCause", Map.of("enum", causeValues)),
                                        // Name and value both required: half a detail says nothing to
                                        // the analyst and only risks a row that can't be stored.
                                        Map.entry("details", Map.of(
                                                "type", "array",
                                                "items", Map.of(
                                                        "type", "object",
                                                        "properties", Map.of(
                                                                "name", Map.of("type", "string"),
                                                                "value", Map.of("type", "string")),
                                                        "required", List.of("name", "value"))))
                                ))
                ),
                "required", List.of("transcription", "visualFindings")
        );
    }

    /** The catalog as the prompt lists it; an empty one says so, and the schema then only allows null. */
    private String promptFor(List<String> claimCauses) {
        String catalog = claimCauses.isEmpty()
                ? "(no hay catálogo disponible: devolvé `describedClaimCause` en null)"
                : String.join("\n", claimCauses.stream().map(cause -> "- " + cause).toList());
        return documentExtractionPrompt.replace(CATALOG_PLACEHOLDER, catalog);
    }

    @Override
    public DocumentExtraction extract(byte[] content, String contentType, List<String> claimCauses) {
        log.info("[LLM] Starting document analysis — model={} contentType={} sizeBytes={} magicBytes={} decodableByJava={}",
                client.model(), contentType, content.length, magicBytesHex(content), isDecodableImage(content));

        List<String> causes = claimCauses == null ? List.of() : claimCauses;
        if (isPdf(contentType, content)) {
            return extractFromPdf(content, causes);
        }
        return extractFromImage(content, causes);
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

    /** Findings are prefixed with their page so the analyst knows which page to open. */
    private DocumentExtraction extractFromPdf(byte[] content, List<String> claimCauses) {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = Math.min(document.getNumberOfPages(), MAX_PDF_PAGES);
            if (document.getNumberOfPages() > MAX_PDF_PAGES) {
                log.warn("[LLM] PDF has {} pages, only analyzing the first {}",
                        document.getNumberOfPages(), MAX_PDF_PAGES);
            }

            log.info("[LLM] PDF has {} page(s) to read", pageCount);
            StringBuilder transcription = new StringBuilder();
            List<String> findings = new ArrayList<>();
            DocumentExtraction.Fields fields = DocumentExtraction.Fields.none();
            DocumentExtraction.Status status = DocumentExtraction.Status.COMPLETE;
            for (int page = 0; page < pageCount; page++) {
                log.info("[LLM] Rendering and reading page {}/{}...", page + 1, pageCount);
                BufferedImage image = renderer.renderImageWithDPI(page, 150);
                DocumentExtraction pageExtraction = extractFromImage(toPng(image), claimCauses);

                if (pageCount > 1) {
                    transcription.append("--- Página ").append(page + 1).append(" ---\n");
                }
                transcription.append(pageExtraction.transcription()).append("\n");

                int pageNumber = page + 1;
                pageExtraction.visualFindings().forEach(finding -> findings.add(
                        pageCount > 1 ? "Página " + pageNumber + ": " + finding : finding));

                fields = mergeFields(fields, pageExtraction.fields());
                // One broken page is enough: its fields could be the ones a rule needed.
                status = status.worst(pageExtraction.status());
            }
            log.info("[LLM] PDF fully read — {} page(s), status {}", pageCount, status);
            return new DocumentExtraction(transcription.toString().trim(), findings, fields, status);
        } catch (IOException e) {
            throw new InvalidClassificationException("Could not render PDF document for analysis", e);
        }
    }

    /** The first page carrying each value wins (e.g. IMEI on the first page, total on the last). */
    private DocumentExtraction.Fields mergeFields(
            DocumentExtraction.Fields accumulated, DocumentExtraction.Fields page) {
        return new DocumentExtraction.Fields(
                accumulated.documentDate() != null ? accumulated.documentDate() : page.documentDate(),
                accumulated.amount() != null ? accumulated.amount() : page.amount(),
                accumulated.itemDescription() != null ? accumulated.itemDescription() : page.itemDescription(),
                accumulated.brand() != null ? accumulated.brand() : page.brand(),
                accumulated.model() != null ? accumulated.model() : page.model(),
                accumulated.imei() != null ? accumulated.imei() : page.imei(),
                accumulated.affectedParty() != null ? accumulated.affectedParty() : page.affectedParty(),
                accumulated.describedClaimCause() != null
                        ? accumulated.describedClaimCause() : page.describedClaimCause(),
                mergeDetails(accumulated.details(), page.details()));
    }

    /**
     * Details accumulate, deduplicated by name <b>and</b> value: repeated headers collapse, but the
     * same name with two values survives as a contradiction worth showing the analyst.
     */
    private List<DocumentExtraction.Detail> mergeDetails(
            List<DocumentExtraction.Detail> accumulated, List<DocumentExtraction.Detail> page) {
        List<DocumentExtraction.Detail> merged = new ArrayList<>(accumulated);
        page.stream().filter(detail -> !merged.contains(detail)).forEach(merged::add);
        return merged;
    }

    private byte[] toPng(BufferedImage image) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new InvalidClassificationException("Could not encode rendered PDF page as image", e);
        }
    }

    /**
     * A broken answer (cut by the output token cap, or a generation stuck repeating digits) is
     * retried before settling for less: the fields it loses are what the consistency rules compare.
     */
    private DocumentExtraction extractFromImage(byte[] imageContent, List<String> claimCauses) {
        String base64 = Base64.getEncoder().encodeToString(imageContent);

        String content = "";
        for (int attempt = 1; attempt <= MAX_EXTRACTION_ATTEMPTS; attempt++) {
            // No thinking: transcription is mechanical, and reasoning would eat the output budget.
            content = client.chat(promptFor(claimCauses), List.of(base64), outputSchema(claimCauses), false);
            DocumentExtraction extraction = parse(content, claimCauses);
            if (extraction != null) {
                log.info("[LLM] Document analysis done — {} chars transcribed, {} visual finding(s)",
                        extraction.transcription().length(), extraction.visualFindings().size());
                log.debug("[LLM] Extraction:\n{}", extraction);
                return extraction;
            }
            log.warn("[LLM] Document analysis attempt {}/{} returned {} ({} chars)", attempt,
                    MAX_EXTRACTION_ATTEMPTS, content.isEmpty() ? "nothing" : "an unparseable answer",
                    content.length());
        }
        return degrade(content);
    }

    /** Null when the answer isn't the requested JSON, so the caller can retry. */
    private DocumentExtraction parse(String contentJson, List<String> claimCauses) {
        if (contentJson.isEmpty()) {
            return null;
        }
        try {
            ModelOutput output = objectMapper.readValue(contentJson, ModelOutput.class);
            String transcription = output.transcription() == null || output.transcription().isBlank()
                    ? UNREADABLE
                    : output.transcription();
            return new DocumentExtraction(transcription, output.visualFindings(), toFields(output.fields(), claimCauses));
        } catch (Exception e) {
            log.debug("[LLM] Could not parse document extraction: {}", e.getMessage());
            return null;
        }
    }

    /**
     * What's left of a broken answer, with no findings or fields: silence beats a made-up finding.
     * The transcription is salvaged from truncated JSON; an answer that isn't JSON at all is prose
     * the model wrote instead, still the document's text. A JSON that yields nothing is never
     * shown — the analyst would be reading the model's syntax.
     */
    private DocumentExtraction degrade(String content) {
        String salvaged = salvageTranscription(content);
        if (salvaged != null) {
            log.warn("[LLM] Document analysis kept only the salvaged transcription ({} chars)", salvaged.length());
            return DocumentExtraction.partial(salvaged);
        }
        String text = content.trim();
        if (!text.isEmpty() && !text.startsWith("{")) {
            log.warn("[LLM] Document analysis answered prose instead of JSON — kept as the transcription");
            return DocumentExtraction.partial(text);
        }
        log.warn("[LLM] Document analysis failed: nothing could be read");
        return DocumentExtraction.failed(UNREADABLE);
    }

    /**
     * Reads the {@code transcription} string out of a JSON cut off mid-way (output token cap), up
     * to its closing quote or, if the cut fell inside it, up to where it stops. Null if absent.
     */
    private String salvageTranscription(String json) {
        Matcher start = TRANSCRIPTION_START.matcher(json);
        if (!start.find()) {
            return null;
        }
        int i = start.end();
        StringBuilder escaped = new StringBuilder();
        while (i < json.length() && json.charAt(i) != '"') {
            char c = json.charAt(i);
            if (c == '\\') {
                // An escape split by the cut is dropped rather than decoded half-way.
                int length = i + 1 < json.length() && json.charAt(i + 1) == 'u' ? 6 : 2;
                if (i + length > json.length()) {
                    break;
                }
                escaped.append(json, i, i + length);
                i += length;
            } else {
                escaped.append(c);
                i++;
            }
        }
        try {
            String transcription = objectMapper.readValue('"' + escaped.toString() + '"', String.class);
            return transcription.isBlank() ? null : transcription.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** Uninterpretable fields stay null; downstream, null means "not stated", never "inconsistent". */
    private DocumentExtraction.Fields toFields(ModelFields fields, List<String> claimCauses) {
        if (fields == null) {
            return DocumentExtraction.Fields.none();
        }
        return new DocumentExtraction.Fields(
                parseDate(fields.documentDate()),
                fields.amount(),
                blankToNull(fields.itemDescription()),
                blankToNull(fields.brand()),
                blankToNull(fields.model()),
                normalizeImei(fields.imei()),
                parseAffectedParty(fields.affectedParty()),
                matchClaimCause(fields.describedClaimCause(), claimCauses),
                toDetails(fields.details()));
    }

    /**
     * Drops details missing a name or value (the columns are NOT NULL, so one would fail the whole
     * document) and truncates long ones to {@code DocumentDetail}'s widths rather than dropping them.
     */
    private List<DocumentExtraction.Detail> toDetails(List<ModelDetail> details) {
        if (details == null) {
            return List.of();
        }
        return details.stream()
                .filter(detail -> detail != null
                        && blankToNull(detail.name()) != null
                        && blankToNull(detail.value()) != null)
                .map(detail -> new DocumentExtraction.Detail(
                        truncate(detail.name().trim(), DETAIL_NAME_MAX),
                        truncate(detail.value().trim(), DETAIL_VALUE_MAX)))
                .toList();
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** A value outside the enum is treated as "the document doesn't say", not as an error. */
    private DocumentExtraction.AffectedParty parseAffectedParty(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DocumentExtraction.AffectedParty.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.debug("[LLM] Unknown affectedParty '{}' — left empty", raw);
            return null;
        }
    }

    /**
     * Back to the catalog's own spelling, or null. The schema already restricts the value, but a
     * provider that doesn't honor the enum could still return "hurto" or something off the list —
     * and the rule compares names, so an unmatched value must read as "the document doesn't say",
     * never as a cause that differs from the declared one.
     */
    private String matchClaimCause(String raw, List<String> claimCauses) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return claimCauses.stream()
                .filter(cause -> cause.equalsIgnoreCase(raw.trim()))
                .findFirst()
                .orElseGet(() -> {
                    log.debug("[LLM] describedClaimCause '{}' is not in the catalog — left empty", raw);
                    return null;
                });
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            log.debug("[LLM] Unparseable document date '{}' — left empty", raw);
            return null;
        }
    }

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

    private record ModelDetail(String name, String value) {}

    private record ModelFields(String documentDate, BigDecimal amount, String itemDescription, String brand,
                               String model, String imei, String affectedParty, String describedClaimCause,
                               List<ModelDetail> details) {}
}
