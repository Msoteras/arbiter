package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.dto.ClassificationRequest;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.services.PromptBuilder;
import ar.edu.utn.frba.arbiter.common.enums.CauseConsistency;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimClassifierImplTest {

    @Mock
    private LlmClient client;

    @Mock
    private PromptBuilder promptBuilder;

    private ClaimClassifierImpl classifier;

    @BeforeEach
    void setUp() {
        classifier = new ClaimClassifierImpl(client, new ObjectMapper(), promptBuilder);
        lenient().when(promptBuilder.buildFullPrompt(any())).thenReturn("prompt");
        lenient().when(client.contextWindow()).thenReturn(32768);
        lenient().when(client.model()).thenReturn("qwen3-vl:8b-instruct");
    }

    @Test
    void classify_stripsTheMarkdownTheModelAddsToItsFactors() {
        modelAnswers("""
                {"classification":"LLM_NO_RECOMIENDA_APROBAR",
                 "factors":["Esto sugiere que **no son documentos reales**, lo cual es grave"],
                 "confidence":0.9}
                """);

        ClassificationResponse response = classifier.classify(someRequest());

        assertThat(response.factors())
                .containsExactly("Esto sugiere que no son documentos reales, lo cual es grave");
    }

    @Test
    void classify_keepsUnderscoresThatAreRealContent() {
        modelAnswers("""
                {"classification":"LLM_SOLICITA_REVISION_MANUAL",
                 "factors":["Falta documento requerido: police_report","La señal last_connection no cierra"],
                 "confidence":0.4}
                """);

        ClassificationResponse response = classifier.classify(someRequest());

        assertThat(response.factors()).containsExactly(
                "Falta documento requerido: police_report",
                "La señal last_connection no cierra");
    }

    @Test
    void classify_readsTheNarrativeConsistencyVerdict() {
        modelAnswers("""
                {"classification":"LLM_RECOMIENDA_APROBAR",
                 "factors":["Documentación completa"],
                 "confidence":0.7,
                 "causeConsistency":"CONTRADICTS",
                 "suggestedClaimCause":"Hurto",
                 "causeEvidence":"lo dejé sobre la mesa y cuando volví no estaba"}
                """);

        ClassificationResponse response = classifier.classify(someRequest());

        assertThat(response.causeConsistency()).isEqualTo(CauseConsistency.CONTRADICTS);
        assertThat(response.suggestedClaimCause()).isEqualTo("Hurto");
        assertThat(response.causeEvidence()).isEqualTo("lo dejé sobre la mesa y cuando volví no estaba");
        // The classifier never reroutes: that's the orchestrator's call, with the coverage data.
        assertThat(response.classification().name()).isEqualTo("LLM_RECOMIENDA_APROBAR");
    }

    @Test
    void classify_blanksBecomeNullSoMatchesCarriesNoLeftovers() {
        modelAnswers("""
                {"classification":"LLM_RECOMIENDA_APROBAR","factors":["ok"],"confidence":0.9,
                 "causeConsistency":"MATCHES","suggestedClaimCause":"","causeEvidence":""}
                """);

        ClassificationResponse response = classifier.classify(someRequest());

        assertThat(response.causeConsistency()).isEqualTo(CauseConsistency.MATCHES);
        assertThat(response.suggestedClaimCause()).isNull();
        assertThat(response.causeEvidence()).isNull();
    }

    @Test
    void classify_missingOrUnknownVerdictDegradesToAmbiguous() {
        // A support signal must never sink a classification the analyst is waiting on — only the
        // classification value itself is worth failing over.
        modelAnswers("""
                {"classification":"LLM_RECOMIENDA_APROBAR","factors":["ok"],"confidence":0.9,
                 "causeConsistency":"NO_IDEA"}
                """);

        assertThat(classifier.classify(someRequest()).causeConsistency())
                .isEqualTo(CauseConsistency.AMBIGUOUS);
    }

    @Test
    @SuppressWarnings("unchecked")
    void classify_restrictsTheSuggestedCauseToTheBranchsCatalog() {
        // This is what makes the answer mappable back to an id without fuzzy matching, and stops
        // the model inventing a cause the insurer never configured.
        modelAnswers("""
                {"classification":"LLM_RECOMIENDA_APROBAR","factors":["ok"],"confidence":0.9,
                 "causeConsistency":"MATCHES","suggestedClaimCause":"","causeEvidence":""}
                """);

        classifier.classify(ClassificationRequest.builder()
                .branch("Celulares")
                .claimCause("Robo en vía pública")
                .insuredItem("Samsung A56")
                .description("Me robaron el celular")
                .claimCauseCatalog(List.of(
                        new ClassificationRequest.ClaimCauseOption(2L, "Robo en vía pública", true),
                        new ClassificationRequest.ClaimCauseOption(3L, "Hurto", false)))
                .build());

        ArgumentCaptor<Map<String, Object>> schema = ArgumentCaptor.forClass(Map.class);
        verify(client).chat(anyString(), anyList(), schema.capture(), anyBoolean());
        Map<String, Object> properties = (Map<String, Object>) schema.getValue().get("properties");
        Map<String, Object> suggested = (Map<String, Object>) properties.get("suggestedClaimCause");
        assertThat((List<String>) suggested.get("enum"))
                .containsExactly("", "Robo en vía pública", "Hurto");
    }

    @Test
    @SuppressWarnings("unchecked")
    void classify_withNoCatalogLeavesTheSuggestedCauseUnconstrained() {
        // An empty enum is an invalid schema; the prompt already tells the model to answer
        // AMBIGUOUS when it has no catalog to choose from.
        modelAnswers("""
                {"classification":"LLM_RECOMIENDA_APROBAR","factors":["ok"],"confidence":0.9,
                 "causeConsistency":"AMBIGUOUS"}
                """);

        classifier.classify(someRequest());

        ArgumentCaptor<Map<String, Object>> schema = ArgumentCaptor.forClass(Map.class);
        verify(client).chat(anyString(), anyList(), schema.capture(), anyBoolean());
        Map<String, Object> properties = (Map<String, Object>) schema.getValue().get("properties");
        assertThat((Map<String, Object>) properties.get("suggestedClaimCause")).doesNotContainKey("enum");
    }

    private void modelAnswers(String content) {
        when(client.chat(anyString(), anyList(), any(), anyBoolean())).thenReturn(content);
    }

    private ClassificationRequest someRequest() {
        return ClassificationRequest.builder()
                .branch("Celulares")
                .claimCause("Robo en vía pública")
                .insuredItem("Samsung A56")
                .description("Me robaron el celular")
                .build();
    }
}
