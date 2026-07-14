package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.exceptions.InvalidClassificationException;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Extracts text from an attached document (OCR) with Qwen3-VL's vision capability, ready to
 * inject into the classification prompt. PDFs are rasterized page by page first (the model
 * only accepts images). HTTP is delegated to {@link OllamaClient}.
 */
@Service
public class OllamaDocumentAnalyzer implements DocumentAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(OllamaDocumentAnalyzer.class);

    /** Qwen3-VL via Ollama only accepts images (jpg/png/webp/...), not PDF — needs rasterizing first. */
    private static final int MAX_PDF_PAGES = 5;

    private final OllamaClient client;
    private final String documentExtractionPrompt;

    public OllamaDocumentAnalyzer(
            OllamaClient client,
            @Value("classpath:prompts/extraccion-documento-v1.md") Resource documentExtractionPromptResource
    ) throws IOException {
        this.client = client;
        this.documentExtractionPrompt = documentExtractionPromptResource.getContentAsString(StandardCharsets.UTF_8);
    }

    @Override
    public String extractText(byte[] content, String contentType) {
        log.info("[Ollama] Starting document analysis — model={} contentType={} sizeBytes={} magicBytes={} decodableByJava={}",
                client.model(), contentType, content.length, magicBytesHex(content), isDecodableImage(content));

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

        String extractedText = client.chat(documentExtractionPrompt, List.of(base64), null);

        if (extractedText.isEmpty()) {
            log.warn("[Ollama] Document analysis returned empty content");
            return "No se pudo extraer contenido del documento adjunto.";
        }

        log.info("[Ollama] Document analysis done — extracted {} chars", extractedText.length());
        log.debug("[Ollama] Extracted text:\n{}", extractedText);
        return extractedText;
    }
}
