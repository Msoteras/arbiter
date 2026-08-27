package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
                new ClassPathResource("prompts/extraccion-documento-v5.md"));
    }

    private void modelAnswers(String content) {
        // false: transcribir no se resuelve razonando, así que la extracción pide el modelo sin
        // thinking (ver OllamaDocumentAnalyzer). Matchear el valor exacto y no anyBoolean() deja
        // que este test falle si alguien lo prende sin querer.
        when(client.chat(anyString(), anyList(), anyMap(), eq(false))).thenReturn(content);
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

    /**
     * The order is the fix, not decoration. The model fills the fields in the order the schema
     * lists them and has nowhere else to write: the grammar only admits valid JSON, so when it
     * wants to reason before answering it does so inside whichever string is open. With
     * {@code fields} last, ~3000 characters of deliberation about {@code imei} and
     * {@code affectedParty} ended up inside {@code transcription} — read by the analyst as if the
     * report said it (measured 20/08).
     *
     * <p>Going back to {@code Map.of} would break this silently: its iteration order is randomized
     * per JVM, so it wouldn't even fail consistently.
     */
    @Test
    void theSchemaAsksForTheFieldsInOrder() {
        modelAnswers("""
                {"transcription": "Factura B 0001-00023456", "visualFindings": []}
                """);

        analyzer.extract(SOME_IMAGE, "image/jpeg");

        ArgumentCaptor<Map<String, Object>> schema = ArgumentCaptor.captor();
        verify(client).chat(anyString(), anyList(), schema.capture(), eq(false));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.getValue().get("properties");
        assertThat(properties.keySet())
                .containsExactly("transcription", "fields", "visualFindings");
    }

    /**
     * {@code fields} has to be required. When it was optional the model dropped it to save effort —
     * measured 21/08 on cases 33 and 20: perfect transcriptions, every field null. Nothing failed;
     * {@code DocumentInconsistencyEvaluator} simply had nothing to compare, so the IMEI-against-item
     * and amount-against-claim checks stopped happening and no log said so.
     */
    @Test
    void theSchemaDemandsTheTypedFields() {
        modelAnswers("""
                {"transcription": "Factura B 0001-00023456", "fields": {}, "visualFindings": []}
                """);

        analyzer.extract(SOME_IMAGE, "image/jpeg");

        ArgumentCaptor<Map<String, Object>> schema = ArgumentCaptor.captor();
        verify(client).chat(anyString(), anyList(), schema.capture(), eq(false));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.getValue().get("required");
        assertThat(required).contains("fields");
    }

    /** The prompt has to ask for the fields, not just the schema — that was the actual regression. */
    @Test
    void thePromptDoesNotTellTheModelToOmitTheFields() throws IOException {
        String prompt = new ClassPathResource("prompts/extraccion-documento-v5.md")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(prompt).contains("no significa que haya que omitir");
        assertThat(prompt).doesNotContain("nada de listar `documentDate`");
    }
}
