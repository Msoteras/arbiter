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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The transcription and the visual findings must come out separate, and an unreadable document
 * must not bring down the classification.
 */
@ExtendWith(MockitoExtension.class)
class DocumentAnalyzerImplTest {

    private static final byte[] SOME_IMAGE = "not-really-an-image".getBytes();

    @Mock
    private LlmClient client;

    private DocumentAnalyzerImpl analyzer;

    @BeforeEach
    void setUp() throws IOException {
        analyzer = new DocumentAnalyzerImpl(
                client,
                new ObjectMapper(),
                new ClassPathResource("prompts/extraccion-documento-v5.md"));
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

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/jpeg");

        assertThat(extraction.transcription()).isEqualTo("Constancia de denuncia N° 4471/26");
        assertThat(extraction.visualFindings()).containsExactly("El sello está pixelado respecto del resto");
    }

    /** The normal case: an ordinary document has no visual findings. */
    @Test
    void aCleanDocumentYieldsNoFindings() {
        modelAnswers("""
                {"transcription": "Factura B 0001-00023456", "visualFindings": []}
                """);

        assertThat(analyzer.extract(SOME_IMAGE, "image/jpeg").visualFindings()).isEmpty();
    }

    /** A malformed answer keeps the raw text and invents no finding. */
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

    @Test
    void blankTranscriptionReadsAsAnUnreadableDocument() {
        modelAnswers("""
                {"transcription": "   ", "visualFindings": []}
                """);

        assertThat(analyzer.extract(SOME_IMAGE, "image/jpeg").transcription())
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

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/png");

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

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/png");

        assertThat(extraction.fields().details())
                .extracting(DocumentExtraction.Detail::name)
                .containsExactly("Comercio");
    }

    @Test
    void aDocumentWithoutDetails_yieldsAnEmptyListNotNull() {
        modelAnswers("""
                {"transcription": "Foto del equipo", "visualFindings": [], "fields": {}}
                """);

        DocumentExtraction extraction = analyzer.extract(SOME_IMAGE, "image/png");

        assertThat(extraction.fields().details()).isEmpty();
    }
}
