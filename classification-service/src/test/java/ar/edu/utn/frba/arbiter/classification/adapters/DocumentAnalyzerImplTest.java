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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The transcription and the visual findings must come out separate, and an unreadable document
 * must not bring down the classification.
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
                new ClassPathResource("prompts/extraccion-documento-v7.md"));
    }

    private void modelAnswers(String content) {
        // Exact `false`, not anyBoolean(), so enabling thinking by accident fails this test.
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

    /** The normal case: an ordinary document has no visual findings. */
    @Test
    void aCleanDocumentYieldsNoFindings() {
        modelAnswers("""
                {"transcription": "Factura B 0001-00023456", "visualFindings": []}
                """);

        assertThat(analyzer.extract(SOME_IMAGE, "image/jpeg", CATALOG).visualFindings()).isEmpty();
    }

    @Test
    void aWellFormedAnswerIsACompleteReadOnTheFirstTry() {
        modelAnswers("""
                {"transcription": "Factura B 0001-00023456", "visualFindings": []}
                """);

        assertThat(analyzer.extract(SOME_IMAGE, "image/jpeg", CATALOG).status())
                .isEqualTo(DocumentExtraction.Status.COMPLETE);
        verify(client, times(1)).chat(anyString(), anyList(), anyMap(), eq(false));
    }

    /** The case that reached production: the first answer is cut, the retry comes back whole. */
    @Test
    void aBrokenAnswerIsRetriedAndTheRetryWins() {
        when(client.chat(anyString(), anyList(), anyMap(), eq(false))).thenReturn(
                "{\"transcription\": \"ACTA DE DENUNCIA\", \"visualFindings\": [], \"fields\": {\"brand\": \"GOO",
                """
                {"transcription": "ACTA DE DENUNCIA", "visualFindings": [],
                 "fields": {"brand": "GOOGLE", "affectedParty": "TITULAR"}}
                """);

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/jpeg", CATALOG);

        assertThat(extraction.status()).isEqualTo(DocumentExtraction.Status.COMPLETE);
        assertThat(extraction.fields().brand()).isEqualTo("GOOGLE");
        assertThat(extraction.fields().affectedParty()).isEqualTo(DocumentExtraction.AffectedParty.TITULAR);
    }

    /** Prose instead of JSON is still the document's text, but its fields were never read. */
    @Test
    void unparseableAnswerDegradesToRawTextWithoutFindings() {
        modelAnswers("Constancia de denuncia, comisaría 15a.");

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/jpeg", CATALOG);

        assertThat(extraction.transcription()).isEqualTo("Constancia de denuncia, comisaría 15a.");
        assertThat(extraction.visualFindings()).isEmpty();
        assertThat(extraction.status()).isEqualTo(DocumentExtraction.Status.PARTIAL);
        verify(client, times(2)).chat(anyString(), anyList(), anyMap(), eq(false));
    }

    /** Output cut by the token cap twice: the analyst sees the document's text, not the JSON around it. */
    @Test
    void truncatedAnswerKeepsOnlyTheTranscription() {
        modelAnswers("""
                {"transcription": "ACTA DE DENUNCIA. Domicilio: piso 6 \\"A\\", C.A.B.A.", "visualFindings": [], "fields": {"brand": "GOO""");

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/jpeg", CATALOG);

        assertThat(extraction.transcription()).isEqualTo("ACTA DE DENUNCIA. Domicilio: piso 6 \"A\", C.A.B.A.");
        assertThat(extraction.visualFindings()).isEmpty();
        assertThat(extraction.fields()).isEqualTo(DocumentExtraction.Fields.none());
        assertThat(extraction.status()).isEqualTo(DocumentExtraction.Status.PARTIAL);
    }

    /** The cut can fall inside the transcription itself, even in the middle of an escape. */
    @Test
    void transcriptionCutMidwayKeepsWhatArrived() {
        modelAnswers("{\"transcription\": \"Comisaría Vecinal 3-B\\nRelato: que siendo las 19:25\\");

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/jpeg", CATALOG);

        assertThat(extraction.transcription()).isEqualTo("Comisaría Vecinal 3-B\nRelato: que siendo las 19:25");
    }

    /** A JSON with nothing to salvage is never shown: the analyst would be reading the model's syntax. */
    @Test
    void brokenJsonWithoutTranscriptionIsAFailedRead() {
        modelAnswers("{\"visualFindings\": [], \"fields\": {\"imei\": \"3552000000000000000000000000");

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/jpeg", CATALOG);

        assertThat(extraction.transcription()).contains("No se pudo extraer contenido");
        assertThat(extraction.status()).isEqualTo(DocumentExtraction.Status.FAILED);
    }

    /** Seen with Gemini: PostgreSQL rejects NUL in text columns, which lost every reading of the case. */
    @Test
    void nulCharactersNeverReachTheExtraction() {
        modelAnswers("""
                {"transcription": "ACTA\\u0000 DE DENUNCIA", "visualFindings": ["sello\\u0000 borroso"],
                 "fields": {"brand": "GOO\\u0000GLE", "details": [{"name": "Folio\\u0000", "value": "214\\u0000"}]}}
                """);

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/jpeg", CATALOG);

        assertThat(extraction.transcription()).isEqualTo("ACTA DE DENUNCIA");
        assertThat(extraction.visualFindings()).containsExactly("sello borroso");
        assertThat(extraction.fields().brand()).isEqualTo("GOOGLE");
        assertThat(extraction.fields().details())
                .containsExactly(new DocumentExtraction.Detail("Folio", "214"));
    }

    @Test
    void emptyAnswerReadsAsAnUnreadableDocument() {
        modelAnswers("");

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/jpeg", CATALOG);

        assertThat(extraction.transcription()).contains("No se pudo extraer contenido");
        assertThat(extraction.visualFindings()).isEmpty();
        assertThat(extraction.status()).isEqualTo(DocumentExtraction.Status.FAILED);
    }

    @Test
    void blankTranscriptionReadsAsAnUnreadableDocument() {
        modelAnswers("""
                {"transcription": "   ", "visualFindings": []}
                """);

        assertThat(analyzer.extract(SOME_IMAGE, "image/jpeg", CATALOG).transcription())
                .contains("No se pudo extraer contenido");
    }

    // ─── details ─────────────────────────────────────────────────────────────────

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

    /** Both columns are NOT NULL: a half-written detail would fail the whole document's insert. */
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
