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
class DocumentAnalyzerImplTest {

    private static final byte[] SOME_IMAGE = "not-really-an-image".getBytes();

    /** The branch's claim causes: the only values describedClaimCause may take. */
    private static final List<String> CATALOG = List.of("Caída", "Hurto", "Robo en vía pública");

    @Mock
    private LlmClient client;

    private DocumentAnalyzerImpl analyzer;

    @BeforeEach
    void setUp() throws IOException {
        analyzer = new DocumentAnalyzerImpl(
                client,
                new ObjectMapper(),
                new ClassPathResource("prompts/extraccion-documento-v6.md"));
    }

    private void modelAnswers(String content) {
        // false: transcribir no se resuelve razonando, así que la extracción pide el modelo sin
        // thinking (ver DocumentAnalyzerImpl). Matchear el valor exacto y no anyBoolean() deja
        // que este test falle si alguien lo prende sin querer.
        when(client.chat(anyString(), anyList(), anyMap(), eq(false))).thenReturn(content);
    }

    @Test
    void splitsTheTranscriptionFromTheVisualFindings() {
        modelAnswers("""
                {"transcription": "Constancia de denuncia N° 4471/26",
                 "visualFindings": ["El sello está pixelado respecto del resto"]}
                """);

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/jpeg", CATALOG);

        assertThat(extraction.transcription()).isEqualTo("Constancia de denuncia N° 4471/26");
        assertThat(extraction.visualFindings()).containsExactly("El sello está pixelado respecto del resto");
    }

    /** El caso normal: un documento común no tiene señales, y la lista vacía es el resultado bueno. */
    @Test
    void aCleanDocumentYieldsNoFindings() {
        modelAnswers("""
                {"transcription": "Factura B 0001-00023456", "visualFindings": []}
                """);

        assertThat(analyzer.extract(SOME_IMAGE, "image/jpeg", CATALOG).visualFindings()).isEmpty();
    }

    /**
     * If the model doesn't respect the schema, the raw text is kept as the transcription and no
     * finding is invented: a malformed answer is no evidence of anything visual.
     */
    @Test
    void unparseableAnswerDegradesToRawTextWithoutFindings() {
        modelAnswers("Constancia de denuncia, comisaría 15a.");

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/jpeg", CATALOG);

        assertThat(extraction.transcription()).isEqualTo("Constancia de denuncia, comisaría 15a.");
        assertThat(extraction.visualFindings()).isEmpty();
    }

    @Test
    void emptyAnswerReadsAsAnUnreadableDocument() {
        modelAnswers("");

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/jpeg", CATALOG);

        assertThat(extraction.transcription()).contains("No se pudo extraer contenido");
        assertThat(extraction.visualFindings()).isEmpty();
    }

    /** Schema respected but the transcription empty: same as not having been able to read it. */
    @Test
    void blankTranscriptionReadsAsAnUnreadableDocument() {
        modelAnswers("""
                {"transcription": "   ", "visualFindings": []}
                """);

        assertThat(analyzer.extract(SOME_IMAGE, "image/jpeg", CATALOG).transcription())
                .contains("No se pudo extraer contenido");
    }

    // ─── details: lo que el documento dice y ninguna regla lee ────────────────────

    @Test
    void readsTheNameValueDetails() {
        modelAnswers("""
                {"transcription": "Factura B 0001-00034521",
                 "visualFindings": [],
                 "fields": {"brand": "Samsung", "model": "Galaxy A56",
                            "details": [{"name": "N° de factura", "value": "0001-00034521"},
                                        {"name": "Comercio", "value": "Frávega S.A."}]}}
                """);

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/png", CATALOG);

        assertThat(extraction.fields().brand()).isEqualTo("Samsung");
        assertThat(extraction.fields().model()).isEqualTo("Galaxy A56");
        assertThat(extraction.fields().details())
                .extracting(DocumentExtraction.Detail::name)
                .containsExactly("N° de factura", "Comercio");
    }

    /**
     * Both columns are NOT NULL, so a half-written detail would cost the whole document's
     * extraction rather than that one row — and an empty label tells the analyst nothing anyway.
     */
    @Test
    void dropsDetailsMissingANameOrAValue() {
        modelAnswers("""
                {"transcription": "...",
                 "visualFindings": [],
                 "fields": {"details": [{"name": "N° de serie", "value": "  "},
                                        {"name": "", "value": "algo"},
                                        {"name": "Comercio", "value": "Frávega S.A."}]}}
                """);

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/png", CATALOG);

        assertThat(extraction.fields().details())
                .extracting(DocumentExtraction.Detail::name)
                .containsExactly("Comercio");
    }

    /** No details at all is the ordinary case — a photo of the broken device states none. */
    @Test
    void aDocumentWithoutDetails_yieldsAnEmptyListNotNull() {
        modelAnswers("""
                {"transcription": "Foto del equipo", "visualFindings": [], "fields": {}}
                """);

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/png", CATALOG);

        assertThat(extraction.fields().details()).isEmpty();
    }

    // ─── describedClaimCause ─────────────────────────────────────────────────────

    /** Back to the catalog's own spelling: the rule downstream compares names. */
    @Test
    void describedClaimCause_isMatchedToTheCatalogSpelling() {
        modelAnswers("""
                {"transcription": "Acta de denuncia — HURTO (art. 162)", "visualFindings": [],
                 "fields": {"describedClaimCause": "hurto"}}
                """);

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/png", CATALOG);

        assertThat(extraction.fields().describedClaimCause()).isEqualTo("Hurto");
    }

    /**
     * A name off the catalog reads as "the document doesn't say", never as a different cause:
     * otherwise a provider that ignores the enum would raise a mismatch warning out of nothing.
     */
    @Test
    void describedClaimCause_offTheCatalog_isLeftEmpty() {
        modelAnswers("""
                {"transcription": "...", "visualFindings": [],
                 "fields": {"describedClaimCause": "Robo con arma de fuego"}}
                """);

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/png", CATALOG);

        assertThat(extraction.fields().describedClaimCause()).isNull();
    }

    /** The schema only lets the model answer with the catalog's names, or null. */
    @Test
    @SuppressWarnings("unchecked")
    void schemaAndPrompt_carryTheCatalog() {
        modelAnswers("""
                {"transcription": "...", "visualFindings": []}
                """);

        analyzer.extract(SOME_IMAGE, "image/png", CATALOG);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> schema = ArgumentCaptor.forClass(Map.class);
        verify(client).chat(prompt.capture(), anyList(), schema.capture(), eq(false));

        Map<String, Object> fields = (Map<String, Object>) ((Map<String, Object>) schema.getValue()
                .get("properties")).get("fields");
        Map<String, Object> cause = (Map<String, Object>) ((Map<String, Object>) fields.get("properties"))
                .get("describedClaimCause");
        assertThat((List<String>) cause.get("enum")).containsExactly("Caída", "Hurto", "Robo en vía pública", null);
        assertThat(prompt.getValue()).contains("- Robo en vía pública").doesNotContain("{{claimCauseCatalog}}");
    }
}
