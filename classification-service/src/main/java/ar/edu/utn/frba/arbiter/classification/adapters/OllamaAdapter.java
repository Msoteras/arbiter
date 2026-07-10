package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.classification.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationRequest;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.exceptions.InvalidClassificationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class OllamaAdapter implements ClaimClassifier, DocumentAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(OllamaAdapter.class);

    private static final Map<String, Object> OUTPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "classification", Map.of("type", "string",
                            "enum", List.of("FALTA_DOCUMENTACION", "LLM_RECOMIENDA_APROBAR",
                                    "LLM_NO_RECOMIENDA_APROBAR", "LLM_SOLICITA_REVISION_MANUAL")),
                    "factors", Map.of("type", "array",
                            "items", Map.of("type", "string")),
                    "confidence", Map.of("type", "number", "minimum", 0, "maximum", 1)
            ),
            "required", List.of("classification", "factors", "confidence")
    );

    /** Qwen3-VL via Ollama only accepts images (jpg/png/webp/...), not PDF — needs rasterizing first. */
    private static final int MAX_PDF_PAGES = 5;

    private final RestClient ollamaRestClient;
    private final OllamaProperties properties;
    private final ObjectMapper objectMapper;
    private final String promptTemplate;
    private final String documentExtractionPrompt;
    private final int numCtx;

    public OllamaAdapter(
            RestClient ollamaRestClient,
            OllamaProperties properties,
            @Value("classpath:prompts/clasificacion-v1.md") Resource promptResource,
            @Value("classpath:prompts/extraccion-documento-v1.md") Resource documentExtractionPromptResource,
            @Value("${arbiter.ollama.num-ctx:8192}") int numCtx
    ) throws IOException {
        this.ollamaRestClient = ollamaRestClient;
        this.properties = properties;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.promptTemplate = promptResource.getContentAsString(StandardCharsets.UTF_8);
        this.documentExtractionPrompt = documentExtractionPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.numCtx = numCtx;
    }

    @Override
    public ClassificationResponse classify(ClassificationRequest request) {
        String prompt = buildPrompt(request);
        int estimatedTokens = prompt.length() / 4;

        log.info("[Ollama] Starting classification — model={} branch='{}' claimCause='{}' estimated_tokens=~{} num_ctx={}",
                properties.model(), request.branch(), request.claimCause(), estimatedTokens, numCtx);
        if (estimatedTokens > numCtx) {
            log.warn("[Ollama] Prompt (~{} tokens) exceeds num_ctx ({}) — Ollama will silently drop the overflow",
                    estimatedTokens, numCtx);
        }
        log.debug("[Ollama] Full prompt sent:\n{}", prompt);

        ChatRequest chatRequest = new ChatRequest(
                properties.model(),
                List.of(new ChatMessage("user", prompt, List.of())),
                false,
                OUTPUT_SCHEMA,
                Map.of("num_ctx", numCtx)
        );

        long start = System.currentTimeMillis();
        log.info("[Ollama] Sending request to {} ...", properties.baseUrl() + "/api/chat");

        byte[] responseBytes = ollamaRestClient.post()
                .uri("/api/chat")
                .body(chatRequest)
                .retrieve()
                .body(byte[].class);

        long latencyMs = System.currentTimeMillis() - start;
        log.info("[Ollama] Stream received in {} ms, reading content...", latencyMs);

        String finalContent = readStreamingResponse(new ByteArrayInputStream(responseBytes));

        if (finalContent.isEmpty()) {
            log.error("[Ollama] Empty response after {} ms", latencyMs);
            throw new InvalidClassificationException("Ollama returned an empty response");
        }

        log.debug("[Ollama] Raw content: {}", finalContent);

        ClassificationResponse result = parseResponse(finalContent);
        log.info("[Ollama] Classification: {} | confidence: {} | factors: {}",
                result.classification(), result.confidence(), result.factors().size());

        return result;
    }

    @Override
    public String extractText(byte[] content, String contentType) {
        log.info("[Ollama] Starting document analysis — model={} contentType={} sizeBytes={} magicBytes={} decodableByJava={}",
                properties.model(), contentType, content.length, magicBytesHex(content), isDecodableImage(content));

        if (isPdf(contentType, content)) {
            return extractTextFromPdf(content);
        }
        return extractTextFromImage(content);
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

    private String extractTextFromPdf(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = Math.min(document.getNumberOfPages(), MAX_PDF_PAGES);
            if (document.getNumberOfPages() > MAX_PDF_PAGES) {
                log.warn("[Ollama] PDF has {} pages, only analyzing the first {}",
                        document.getNumberOfPages(), MAX_PDF_PAGES);
            }

            StringBuilder combined = new StringBuilder();
            for (int page = 0; page < pageCount; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, 150);
                String pageText = extractTextFromImage(toPng(image));
                if (pageCount > 1) {
                    combined.append("--- Página ").append(page + 1).append(" ---\n");
                }
                combined.append(pageText).append("\n");
            }
            return combined.toString().trim();
        } catch (IOException e) {
            throw new InvalidClassificationException("Could not render PDF document for analysis", e);
        }
    }

    private byte[] toPng(BufferedImage image) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new InvalidClassificationException("Could not encode rendered PDF page as image", e);
        }
    }

    private String extractTextFromImage(byte[] imageContent) {
        String base64 = Base64.getEncoder().encodeToString(imageContent);

        ChatRequest chatRequest = new ChatRequest(
                properties.model(),
                List.of(new ChatMessage("user", documentExtractionPrompt, List.of(base64))),
                false,
                null,
                Map.of("num_ctx", numCtx)
        );

        long start = System.currentTimeMillis();

        byte[] responseBytes = ollamaRestClient.post()
                .uri("/api/chat")
                .body(chatRequest)
                .retrieve()
                .body(byte[].class);

        long latencyMs = System.currentTimeMillis() - start;
        String extractedText = readStreamingResponse(new ByteArrayInputStream(responseBytes));

        if (extractedText.isEmpty()) {
            log.warn("[Ollama] Document analysis returned empty content after {} ms", latencyMs);
            return "No se pudo extraer contenido del documento adjunto.";
        }

        log.info("[Ollama] Document analysis done in {} ms — extracted {} chars", latencyMs, extractedText.length());
        log.debug("[Ollama] Extracted text:\n{}", extractedText);
        return extractedText;
    }

    private String readStreamingResponse(InputStream inputStream) {
        StringBuilder fullContent = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    try {
                        // Each line is a valid JSON: { "message": { "content": "..." }, "done": false }
                        Map<String, Object> chunk = objectMapper.readValue(line, Map.class);
                        Map<String, Object> message = (Map<String, Object>) chunk.get("message");
                        if (message != null) {
                            String content = (String) message.get("content");
                            if (content != null) {
                                fullContent.append(content);
                            }
                        }
                    } catch (IOException e) {
                        log.warn("[Ollama] Could not parse chunk (continuing): {}", line.substring(0, Math.min(100, line.length())));
                    }
                }
            }
        } catch (IOException e) {
            log.error("[Ollama] Error reading response stream", e);
            throw new InvalidClassificationException("Error reading Ollama response: " + e.getMessage(), e);
        }

        String result = fullContent.toString().trim();
        if (result.isEmpty()) {
            log.warn("[Ollama] Stream processed but content is empty");
        }
        return result;
    }

    private String buildPrompt(ClassificationRequest request) {
        String attachmentsText = request.attachmentsOcr() == null || request.attachmentsOcr().isEmpty()
                ? "Sin documentos adjuntos"
                : String.join("\n\n---\n\n", request.attachmentsOcr());

        String history = request.insuredHistory() == null
                ? "Sin historial previo disponible"
                : request.insuredHistory();

        String rules = request.insurerRules() == null
                ? "Sin rules adicionales configuradas"
                : request.insurerRules();

        return promptTemplate
                .replace("{{branch}}", request.branch())
                .replace("{{product}}", request.product())
                .replace("{{claimCause}}", request.claimCause())
                .replace("{{insuredItem}}", request.insuredItem())
                .replace("{{description}}", request.description())
                .replace("{{attachmentsOcr}}", attachmentsText)
                .replace("{{insurerRules}}", rules)
                .replace("{{insuredHistory}}", history);
    }

    private ClassificationResponse parseResponse(String contentJson) {
        try {
            ModelOutput output = objectMapper.readValue(contentJson, ModelOutput.class);
            Classification classification = Classification.valueOf(output.classification());
            return new ClassificationResponse(classification, output.factors(), output.confidence(), false);
        } catch (IllegalArgumentException e) {
            throw new InvalidClassificationException(
                    "The model returned an invalid classification value: " + contentJson, e);
        } catch (Exception e) {
            throw new InvalidClassificationException(
                    "Could not parse model response: " + contentJson, e);
        }
    }

    // --- Internal records for Ollama API protocol ---

    private record ChatMessage(String role, String content, List<String> images) {}

    private record ChatRequest(String model, List<ChatMessage> messages, boolean stream,
                               Map<String, Object> format, Map<String, Object> options) {}

    private record ModelOutput(String classification, List<String> factors, double confidence) {}
}
