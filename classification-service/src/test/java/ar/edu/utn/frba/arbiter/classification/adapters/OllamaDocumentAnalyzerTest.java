package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The extraction pass is the only moment anyone looks at the image (D5): the classifier works on
 * text. What's tested here is that both halves — what the document says and what the image looks
 * like — come out separate, and that a document that couldn't be read doesn't bring down the whole
 * classification.
 */
@ExtendWith(MockitoExtension.class)
class OllamaDocumentAnalyzerTest {

    private static final byte[] SOME_IMAGE = "not-really-an-image".getBytes();

    @Mock
    private OllamaClient client;

    private OllamaDocumentAnalyzer analyzer;

    @BeforeEach
    void setUp() throws IOException {
        analyzer = new OllamaDocumentAnalyzer(
                client,
                new ObjectMapper(),
                new ClassPathResource("prompts/extraccion-documento-v3.md"));
    }

    private void modelAnswers(String content) {
        when(client.chat(anyString(), anyList(), anyMap())).thenReturn(content);
    }

    @Test
    void splitsTheTranscriptionFromTheVisualFindings() {
        modelAnswers("""
                {"transcription": "Constancia de denuncia N° 4471/26",
                 "visualFindings": ["El sello está pixelado respecto del resto"]}
                """);

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/jpeg");

        assertThat(extraction.transcription()).isEqualTo("Constancia de denuncia N° 4471/26");
        assertThat(extraction.visualFindings()).containsExactly("El sello está pixelado respecto del resto");
    }

    /** El caso normal: un documento común no tiene señales, y la lista vacía es el resultado bueno. */
    @Test
    void aCleanDocumentYieldsNoFindings() {
        modelAnswers("""
                {"transcription": "Factura B 0001-00023456", "visualFindings": []}
                """);

        assertThat(analyzer.extract(SOME_IMAGE, "image/jpeg").visualFindings()).isEmpty();
    }

    /**
     * If the model doesn't respect the schema, the raw text is kept as the transcription and no
     * finding is invented: a malformed answer is no evidence of anything visual.
     */
    @Test
    void unparseableAnswerDegradesToRawTextWithoutFindings() {
        modelAnswers("Constancia de denuncia, comisaría 15a.");

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/jpeg");

        assertThat(extraction.transcription()).isEqualTo("Constancia de denuncia, comisaría 15a.");
        assertThat(extraction.visualFindings()).isEmpty();
    }

    @Test
    void emptyAnswerReadsAsAnUnreadableDocument() {
        modelAnswers("");

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/jpeg");

        assertThat(extraction.transcription()).contains("No se pudo extraer contenido");
        assertThat(extraction.visualFindings()).isEmpty();
    }

    /** Schema respected but the transcription empty: same as not having been able to read it. */
    @Test
    void blankTranscriptionReadsAsAnUnreadableDocument() {
        modelAnswers("""
                {"transcription": "   ", "visualFindings": []}
                """);

        assertThat(analyzer.extract(SOME_IMAGE, "image/jpeg").transcription())
                .contains("No se pudo extraer contenido");
    }
}
