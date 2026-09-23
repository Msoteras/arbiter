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

    /** Mirror {@code DocumentDetail}'s columns: a longer value is trimmed, never dropped. */
    private static final int DETAIL_NAME_MAX = 100;
    private static final int DETAIL_VALUE_MAX = 500;

    /** Without a forced shape, the model's own observations blend into the transcription. */
    private static final Map<String, Object> OUTPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "transcription", Map.of("type", "string"),
                    "visualFindings", Map.of("type", "array", "items", Map.of("type", "string")),
                    // Not required, so the model doesn't invent what the document lacks.
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

    private final LlmClient client;
    private final ObjectMapper objectMapper;
    private final String documentExtractionPrompt;

    public DocumentAnalyzerImpl(
            LlmClient client,
            ObjectMapper objectMapper,
            @Value("classpath:prompts/extraccion-documento-v5.md") Resource documentExtractionPromptResource
    ) throws IOException {
        this.client = client;
        this.objectMapper = objectMapper;
        this.documentExtractionPrompt = documentExtractionPromptResource.getContentAsString(StandardCharsets.UTF_8);
    }

    @Override
    public DocumentExtraction extract(byte[] content, String contentType) {
        log.info("[LLM] Starting document analysis — model={} contentType={} sizeBytes={} magicBytes={} decodableByJava={}",
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

    /** Findings are prefixed with their page so the analyst knows which page to open. */
    private DocumentExtraction extractFromPdf(byte[] content) {
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
            for (int page = 0; page < pageCount; page++) {
                log.info("[LLM] Rendering and reading page {}/{}...", page + 1, pageCount);
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
            log.info("[LLM] PDF fully read — {} page(s)", pageCount);
            return new DocumentExtraction(transcription.toString().trim(), findings, fields);
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

    private DocumentExtraction extractFromImage(byte[] imageContent) {
        String base64 = Base64.getEncoder().encodeToString(imageContent);

        // No thinking: transcription is mechanical, and reasoning would eat the num_predict budget.
        String content = client.chat(documentExtractionPrompt, List.of(base64), OUTPUT_SCHEMA, false);

        if (content.isEmpty()) {
            log.warn("[LLM] Document analysis returned empty content");
            return DocumentExtraction.of(UNREADABLE);
        }

        DocumentExtraction extraction = parse(content);
        log.info("[LLM] Document analysis done — {} chars transcribed, {} visual finding(s)",
                extraction.transcription().length(), extraction.visualFindings().size());
        log.debug("[LLM] Extraction:\n{}", extraction);
        return extraction;
    }

    /** An unparseable answer degrades to its raw text with no findings: silence beats a made-up finding. */
    private DocumentExtraction parse(String contentJson) {
        try {
            ModelOutput output = objectMapper.readValue(contentJson, ModelOutput.class);
            String transcription = output.transcription() == null || output.transcription().isBlank()
                    ? UNREADABLE
                    : output.transcription();
            return new DocumentExtraction(transcription, output.visualFindings(), toFields(output.fields()));
        } catch (Exception e) {
            log.warn("[LLM] Could not parse document extraction, keeping the raw text: {}", e.getMessage());
            return DocumentExtraction.of(contentJson);
        }
    }

    /** Uninterpretable fields stay null; downstream, null means "not stated", never "inconsistent". */
    private DocumentExtraction.Fields toFields(ModelFields fields) {
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
                               String model, String imei, String affectedParty, List<ModelDetail> details) {}
}
