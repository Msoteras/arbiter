package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.cases.support.CaseStates;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class ClassificationServiceClientTest {

    private static final String BASE_URL = "http://classification-service:8082";
    private static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-for-hs256";

    @Mock
    private CaseStatusService caseStatusService;

    @Mock
    private HttpServletRequest currentRequest;

    private MockRestServiceServer server;
    private ClassificationServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ClassificationServiceClient(builder, caseStatusService, BASE_URL, currentRequest, JWT_SECRET);
    }

    @Test
    void faltaDocumentacion_transitionsToAwaitingDocumentation() {
        Case entity = pendingCase(3L);
        expectPoll(3L, "FALTA_DOCUMENTACION", "1.0",
                "[\"Falta documento requerido: police_report\"]", false);

        boolean resolved = client.refreshClassification(entity);

        // La recomendación ya no se copia al expediente (vive en llm_analysis); lo observable de
        // este lado es a qué estado lo mueve.
        assertThat(resolved).isTrue();
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
    void llmClassification_cachesForensicReport() {
        Case entity = pendingCase(11L);
        server.expect(requestTo(BASE_URL + "/api/v1/claims/11"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {
                          "caseId": 11,
                          "classification": "LLM_SOLICITA_REVISION_MANUAL",
                          "confidence": 0.6,
                          "factors": ["Imagen con posible reutilización"],
                          "deterministicFastTrack": false,
                          "forensicReport": {
                            "imagesAnalyzed": 1,
                            "webSearchesPerformed": 0,
                            "findings": [
                              {
                                "label": "item_photo-0",
                                "documentType": "item_photo",
                                "internalMatches": [
                                  {"matchedCaseId": 4, "matchedDocumentType": "item_photo",
                                   "matchedFilename": "IMG_2831.jpg", "similarity": 0.97}
                                ],
                                "webFinding": null
                              }
                            ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        boolean resolved = client.refreshClassification(entity);

        assertThat(resolved).isTrue();
        assertThat(entity.getForensicReport()).isNotNull();
        assertThat(entity.getForensicReport().imagesAnalyzed()).isEqualTo(1);
        assertThat(entity.getForensicReport().findings()).hasSize(1);
        var match = entity.getForensicReport().findings().get(0).internalMatches().get(0);
        assertThat(match.matchedCaseId()).isEqualTo(4L);
        assertThat(match.similarity()).isEqualTo(0.97);
    }

    @Test
    void llmClassification_withNoForensicReport_leavesItNull() {
        Case entity = pendingCase(2L);
        expectPoll(2L, "LLM_NO_RECOMIENDA_APROBAR", "0.95", "[\"Reincidente\"]", false);

        client.refreshClassification(entity);

        assertThat(entity.getForensicReport()).isNull();
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
        // La guarda pasó a mirar el estado: salir de PENDING_CLASSIFICATION es justamente lo que
        // hace este método cuando llega el resultado, así que cualquier otro estado ya se resolvió.
        Case entity = pendingCase(1L);
        entity.setCurrentStatus(CaseStates.of(CaseStatus.PENDING_ANALYST_REVIEW));

        boolean resolved = client.refreshClassification(entity);

        assertThat(resolved).isTrue();
        server.verify(); // no HTTP request expected
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void refreshClassification_sendsSelfSignedServiceToken() {
        Case entity = pendingCase(7L);
        server.expect(requestTo(BASE_URL + "/api/v1/claims/7"))
                .andExpect(method(GET))
                .andExpect(request -> {
                    String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                    assertThat(authHeader).startsWith("Bearer ");
                    var claims = io.jsonwebtoken.Jwts.parser()
                            .verifyWith(JwtSupport.key(JWT_SECRET)).build()
                            .parseSignedClaims(authHeader.substring(7))
                            .getPayload();
                    assertThat(claims.getSubject()).isEqualTo("cases-service-scheduler");
                })
                .andRespond(withSuccess("""
                        {"caseId": 7, "classification": null, "confidence": null, "factors": null, "deterministicFastTrack": false}
                        """, MediaType.APPLICATION_JSON));

        client.refreshClassification(entity);

        server.verify();
    }

    @Test
    void analyzeAndPersist_forwardsIncomingAuthorizationHeader() {
        when(currentRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer original-user-token");
        Case entity = pendingCase(9L);
        server.expect(requestTo(BASE_URL + "/api/v1/claims"))
                .andExpect(method(POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer original-user-token"))
                .andRespond(withSuccess());

        client.analyzeAndPersist(entity, List.<CaseDocument>of());

        server.verify();
    }

    /**
     * The startup recovery sweep has no request behind it — touching {@code currentRequest} there
     * throws "No thread-bound request found" — so this path always signs a service token instead
     * of resolving the header off it, unlike {@link #analyzeAndPersist_forwardsIncomingAuthorizationHeader}.
     */
    @Test
    void analyzeAndPersistAsSystem_signsAServiceTokenInsteadOfTouchingTheRequest() {
        Case entity = pendingCase(10L);
        server.expect(requestTo(BASE_URL + "/api/v1/claims"))
                .andExpect(method(POST))
                .andExpect(request -> {
                    String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                    assertThat(authHeader).startsWith("Bearer ");
                    var claims = io.jsonwebtoken.Jwts.parser()
                            .verifyWith(JwtSupport.key(JWT_SECRET)).build()
                            .parseSignedClaims(authHeader.substring(7))
                            .getPayload();
                    assertThat(claims.getSubject()).isEqualTo("cases-service-recovery");
                })
                .andRespond(withSuccess());

        client.analyzeAndPersistAsSystem(entity, List.<CaseDocument>of());

        server.verify();
        verifyNoInteractions(currentRequest);
    }

    /**
     * La excepción al reenvío: acá se firma un token de servicio. El analystId lo resuelve este
     * módulo contra claims_analyst, así que con el del usuario el endpoint quedaba alcanzable
     * directo y un analista podía firmar la decisión a nombre de otro.
     */
    @Test
    void forwardAnalystDecision_signsAServiceTokenInsteadOfForwardingTheUsers() {
        // Sin stub del header entrante a propósito: no se lee.
        server.expect(requestTo(BASE_URL + "/api/v1/claims/9/decision"))
                .andExpect(method(POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, not(equalTo("Bearer original-user-token"))))
                .andExpect(header(HttpHeaders.AUTHORIZATION, startsWith("Bearer ")))
                .andRespond(withSuccess());

        client.forwardAnalystDecision(9L, new AnalystDecisionRequest(1L, "APPROVE", null, null));

        server.verify();
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
                .claimCause(CaseFixtures.claimCause("Celulares", "Robo en vía pública"))
                .declaredItem("iPhone 16 Pro")
                .insured(CaseFixtures.insured("42.987.654", "Marcos", "Díaz"))
                .policy(CaseFixtures.policy("POL-CEL-2026-042", "Celular Protegido Premium"))
                .coverage(CaseFixtures.coverage("Celulares"))
                .description("Test case")
                .occurredAt(LocalDateTime.of(2026, 6, 12, 18, 30))
                .eventAddress("CABA")
                .claimedAmount(new BigDecimal("1200000"))
                .currentStatus(CaseStates.of(CaseStatus.PENDING_CLASSIFICATION))
                .build();
    }
}
