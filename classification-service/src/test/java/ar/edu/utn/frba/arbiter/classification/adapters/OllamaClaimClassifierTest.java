package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.dto.ClassificationRequest;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.services.PromptBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OllamaClaimClassifierTest {

    @Mock
    private OllamaClient client;

    @Mock
    private PromptBuilder promptBuilder;

    private OllamaClaimClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new OllamaClaimClassifier(client, new ObjectMapper(), promptBuilder);
        lenient().when(promptBuilder.buildFullPrompt(any())).thenReturn("prompt");
        lenient().when(client.numCtx()).thenReturn(32768);
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
