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
 * La pasada de extracción es el único momento en que alguien mira la imagen (D5): el clasificador
 * trabaja sobre texto. Lo que se prueba acá es que las dos mitades —lo que el documento dice y lo
 * que la imagen aparenta— salgan separadas, y que un documento que no se pudo leer no tumbe la
 * clasificación entera.
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
     * Si el modelo no respeta el schema, se conserva el texto crudo como transcripción y no se
     * inventa ningún hallazgo: una respuesta malformada no es evidencia de nada visual.
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

    /** Schema respetado pero transcripción vacía: es lo mismo que no haber podido leerlo. */
    @Test
    void blankTranscriptionReadsAsAnUnreadableDocument() {
        modelAnswers("""
                {"transcription": "   ", "visualFindings": []}
                """);

        assertThat(analyzer.extract(SOME_IMAGE, "image/jpeg").transcription())
                .contains("No se pudo extraer contenido");
    }
}
