package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class ClassificationServiceClientTest {

    private static final String BASE_URL = "http://classification-service:8082";

    @Mock
    private CaseStatusService caseStatusService;

    private MockRestServiceServer server;
    private ClassificationServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ClassificationServiceClient(builder, caseStatusService, BASE_URL);
    }

    @Test
    void faltaDocumentacion_transitionsToAwaitingDocumentation() {
        Case entity = pendingCase(3L);
        expectPoll(3L, "FALTA_DOCUMENTACION", "1.0",
                "[\"Falta documento requerido: police_report\"]", false);

        boolean resolved = client.refreshClassification(entity);

        assertThat(resolved).isTrue();
        assertThat(entity.getAnalysisClassification()).isEqualTo(Classification.FALTA_DOCUMENTACION);
        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.AWAITING_DOCUMENTATION),
                eq(StatusChangeActor.SYSTEM), any());
    }

    @Test
    void llmClassification_transitionsToAnalystReview() {
        Case entity = pendingCase(2L);
        expectPoll(2L, "LLM_NO_RECOMIENDA_APROBAR", "0.95", "[\"Reincidente\"]", false);

        boolean resolved = client.refreshClassification(entity);

        assertThat(resolved).isTrue();
        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.PENDING_ANALYST_REVIEW),
                eq(StatusChangeActor.SYSTEM), any());
    }

    @Test
    void fastTrack_transitionsToAnalystReview() {
        Case entity = pendingCase(1L);
        expectPoll(1L, "FAST_TRACK", "1.0", "[\"Monto dentro del límite\"]", true);

        boolean resolved = client.refreshClassification(entity);

        assertThat(resolved).isTrue();
        assertThat(entity.getDeterministicFastTrack()).isTrue();
        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.PENDING_ANALYST_REVIEW),
                eq(StatusChangeActor.SYSTEM), any());
    }

    @Test
    void classificationNotReadyYet_returnsFalseAndDoesNotTransition() {
        Case entity = pendingCase(5L);
        expectPoll(5L, "null", "null", "null", false);

        boolean resolved = client.refreshClassification(entity);

        assertThat(resolved).isFalse();
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void alreadyClassified_returnsTrueWithoutCallingOrTransitioning() {
        Case entity = pendingCase(1L);
        entity.setAnalysisClassification(Classification.FAST_TRACK);

        boolean resolved = client.refreshClassification(entity);

        assertThat(resolved).isTrue();
        server.verify(); // no HTTP request expected
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    private void expectPoll(long caseId, String classification, String confidence,
                            String factors, boolean fastTrack) {
        server.expect(requestTo(BASE_URL + "/api/v1/claims/" + caseId))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {
                          "caseId": %d,
                          "classification": %s,
                          "confidence": %s,
                          "factors": %s,
                          "deterministicFastTrack": %b
                        }
                        """.formatted(caseId,
                                quoteOrNull(classification), confidence, factors, fastTrack),
                        MediaType.APPLICATION_JSON));
    }

    private String quoteOrNull(String value) {
        return "null".equals(value) ? "null" : "\"" + value + "\"";
    }

    private Case pendingCase(Long id) {
        return Case.builder()
                .id(id)
                .branch("Celulares")
                .product("Celular Protegido Premium")
                .claimCause("Robo en vía pública")
                .insuredItem("iPhone 16 Pro")
                .insuredId("42.987.654")
                .policyNumber("POL-CEL-2026-042")
                .description("Test case")
                .eventDate(LocalDateTime.of(2026, 6, 12, 18, 30))
                .eventLocation("CABA")
                .claimedAmount(new BigDecimal("1200000"))
                .status(CaseStatus.PENDING_CLASSIFICATION)
                .build();
    }
}
