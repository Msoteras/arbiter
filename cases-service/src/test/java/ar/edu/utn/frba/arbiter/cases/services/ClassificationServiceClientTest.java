package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseStatusHistory;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseStatusHistoryRepository;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.cases.support.CaseStates;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class ClassificationServiceClientTest {

    private static final String BASE_URL = "http://classification-service:8082";
    private static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-for-hs256";

    @Mock
    private CaseStatusService caseStatusService;

    @Mock
    private HttpServletRequest currentRequest;

    @Mock
    private CaseRepository caseRepository;

    @Mock
    private CaseStatusHistoryRepository caseStatusHistoryRepository;

    private MockRestServiceServer server;
    private ClassificationServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ClassificationServiceClient(builder, caseStatusService, caseRepository,
                caseStatusHistoryRepository, BASE_URL, currentRequest, JWT_SECRET);
    }

    /**
     * The sweep wins the turn: the CAS returns the moved case. Returning the same instance (instead
     * of a re-read) lets tests keep inspecting what gets cached on it.
     */
    private void winsTheTurn(Case entity, CaseStatus target) {
        when(caseStatusService.transitionIfStillIn(eq(entity), eq(CaseStatus.PENDING_CLASSIFICATION),
                eq(target), eq(StatusChangeActor.SYSTEM), any()))
                .thenReturn(Optional.of(entity));
    }

    @Test
    void faltaDocumentacion_transitionsToAwaitingDocumentation() {
        Case entity = pendingCase(3L);
        winsTheTurn(entity, CaseStatus.AWAITING_DOCUMENTATION);
        expectPoll(3L, "FALTA_DOCUMENTACION", "1.0",
                "[\"Falta documento requerido: police_report\"]", false);

        boolean resolved = client.refreshClassification(entity);

        // The recommendation lives in llm_analysis, not on the case: what's observable here is the
        // target status.
        assertThat(resolved).isTrue();
        verify(caseStatusService).transitionIfStillIn(eq(entity), eq(CaseStatus.PENDING_CLASSIFICATION),
                eq(CaseStatus.AWAITING_DOCUMENTATION), eq(StatusChangeActor.SYSTEM), any());
    }

    @Test
    void llmClassification_transitionsToAnalystReview() {
        Case entity = pendingCase(2L);
        winsTheTurn(entity, CaseStatus.PENDING_ANALYST_REVIEW);
        expectPoll(2L, "LLM_NO_RECOMIENDA_APROBAR", "0.95", "[\"Reincidente\"]", false);

        boolean resolved = client.refreshClassification(entity);

        assertThat(resolved).isTrue();
        verify(caseStatusService).transitionIfStillIn(eq(entity), eq(CaseStatus.PENDING_CLASSIFICATION),
                eq(CaseStatus.PENDING_ANALYST_REVIEW), eq(StatusChangeActor.SYSTEM), any());
    }

    @Test
    void fastTrack_transitionsToAnalystReview() {
        Case entity = pendingCase(1L);
        winsTheTurn(entity, CaseStatus.PENDING_ANALYST_REVIEW);
        expectPoll(1L, "FAST_TRACK", "1.0", "[\"Monto dentro del límite\"]", true);

        boolean resolved = client.refreshClassification(entity);

        assertThat(resolved).isTrue();
        assertThat(entity.getDeterministicFastTrack()).isTrue();
        verify(caseStatusService).transitionIfStillIn(eq(entity), eq(CaseStatus.PENDING_CLASSIFICATION),
                eq(CaseStatus.PENDING_ANALYST_REVIEW), eq(StatusChangeActor.SYSTEM), any());
    }

    @Test
    void llmClassification_cachesForensicReport() {
        Case entity = pendingCase(11L);
        winsTheTurn(entity, CaseStatus.PENDING_ANALYST_REVIEW);
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
        winsTheTurn(entity, CaseStatus.PENDING_ANALYST_REVIEW);
        expectPoll(2L, "LLM_NO_RECOMIENDA_APROBAR", "0.95", "[\"Reincidente\"]", false);

        client.refreshClassification(entity);

        assertThat(entity.getForensicReport()).isNull();
    }

    /**
     * Several schedulers may sweep the same database, so two can reach this point with the same
     * PENDING_CLASSIFICATION copy. The one that loses the CAS must back off without writing, or
     * case_status_history gets duplicate rows and the insured duplicate mails.
     */
    @Test
    void anotherSweepAlreadyResolvedIt_writesNothing() {
        Case entity = pendingCase(12L);
        when(caseStatusService.transitionIfStillIn(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        expectPoll(12L, "LLM_RECOMIENDA_APROBAR", "0.9", "[\"Sin observaciones\"]", true);

        boolean resolved = client.refreshClassification(entity);

        assertThat(resolved).isTrue();
        // Not even the case cache is touched: the loser's copy is stale and saving it would
        // overwrite the whole row the winner left.
        assertThat(entity.getDeterministicFastTrack()).isFalse();
        verifyNoInteractions(caseRepository);
    }

    @Test
    void classificationNotReadyYet_returnsFalseAndDoesNotTransition() {
        Case entity = pendingCase(5L);
        expectPoll(5L, "null", "null", "null", false);

        boolean resolved = client.refreshClassification(entity);

        assertThat(resolved).isFalse();
        verify(caseStatusService, never()).transitionIfStillIn(any(), any(), any(), any(), any());
    }

    /**
     * A case re-entering PENDING_CLASSIFICATION (e.g. after missing documents were uploaded) can be
     * polled before the new run finishes, and classification-service then answers with the old
     * llm_analysis row (append-only). Acting on it would reapply a stale verdict.
     */
    @Test
    void resultOlderThanTheCurrentRound_isTreatedAsNotReadyYet() {
        Case entity = pendingCase(23L);
        Instant enteredPendingClassificationAt = Instant.parse("2026-09-13T00:32:47Z");
        Instant staleAnalyzedAt = enteredPendingClassificationAt.minus(90, ChronoUnit.SECONDS);
        when(caseStatusHistoryRepository.findFirstByCaseIdAndFinalStatus_IdOrderByChangedAtDesc(any(), any()))
                .thenReturn(Optional.of(CaseStatusHistory.builder()
                        .changedAt(enteredPendingClassificationAt)
                        .build()));
        server.expect(requestTo(BASE_URL + "/api/v1/claims/23"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {
                          "caseId": 23,
                          "classification": "FALTA_DOCUMENTACION",
                          "confidence": 1.0,
                          "factors": ["Falta documento requerido: item_photo"],
                          "deterministicFastTrack": false,
                          "analyzedAt": "%s"
                        }
                        """.formatted(staleAnalyzedAt), MediaType.APPLICATION_JSON));

        boolean resolved = client.refreshClassification(entity);

        assertThat(resolved).isFalse();
        verify(caseStatusService, never()).transitionIfStillIn(any(), any(), any(), any(), any());
        verifyNoInteractions(caseRepository);
    }

    /** The counterpart: once a fresh analysis lands, the same result shape is acted on normally. */
    @Test
    void resultAtOrAfterTheCurrentRound_isActedOn() {
        Case entity = pendingCase(23L);
        Instant enteredPendingClassificationAt = Instant.parse("2026-09-13T00:32:47Z");
        Instant freshAnalyzedAt = enteredPendingClassificationAt.plus(90, ChronoUnit.SECONDS);
        winsTheTurn(entity, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseStatusHistoryRepository.findFirstByCaseIdAndFinalStatus_IdOrderByChangedAtDesc(any(), any()))
                .thenReturn(Optional.of(CaseStatusHistory.builder()
                        .changedAt(enteredPendingClassificationAt)
                        .build()));
        server.expect(requestTo(BASE_URL + "/api/v1/claims/23"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {
                          "caseId": 23,
                          "classification": "LLM_NO_RECOMIENDA_APROBAR",
                          "confidence": 0.95,
                          "factors": ["Monto reclamado supera la suma asegurada"],
                          "deterministicFastTrack": false,
                          "analyzedAt": "%s"
                        }
                        """.formatted(freshAnalyzedAt), MediaType.APPLICATION_JSON));

        boolean resolved = client.refreshClassification(entity);

        assertThat(resolved).isTrue();
        verify(caseStatusService).transitionIfStillIn(eq(entity), eq(CaseStatus.PENDING_CLASSIFICATION),
                eq(CaseStatus.PENDING_ANALYST_REVIEW), eq(StatusChangeActor.SYSTEM), any());
    }

    @Test
    void alreadyClassified_returnsTrueWithoutCallingOrTransitioning() {
        // Leaving PENDING_CLASSIFICATION is exactly what this method does when the result arrives,
        // so any other status means it was already resolved.
        Case entity = pendingCase(1L);
        entity.setCurrentStatus(CaseStates.of(CaseStatus.PENDING_ANALYST_REVIEW));

        boolean resolved = client.refreshClassification(entity);

        assertThat(resolved).isTrue();
        server.verify(); // no HTTP request expected
        verify(caseStatusService, never()).transitionIfStillIn(any(), any(), any(), any(), any());
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
     * The exception to forwarding: a service token is signed here. This module resolves the
     * analystId, so with the user's token the endpoint would be reachable directly and an analyst
     * could sign a decision on someone else's behalf.
     */
    @Test
    void forwardAnalystDecision_signsAServiceTokenInsteadOfForwardingTheUsers() {
        // No stub for the incoming header on purpose: it isn't read.
        server.expect(requestTo(BASE_URL + "/api/v1/claims/9/decision"))
                .andExpect(method(POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, not(equalTo("Bearer original-user-token"))))
                .andExpect(header(HttpHeaders.AUTHORIZATION, startsWith("Bearer ")))
                .andRespond(withSuccess());

        client.forwardAnalystDecision(9L, new AnalystDecisionRequest(1L, "APPROVE", null, null, null));

        server.verify();
    }

    /**
     * An empty list states "no rule ran", which is a fact about the classification. If they couldn't
     * be read we know nothing, and that's null: the screen says different things for each.
     */
    @Test
    void ruleResults_whenTheReadFails_comeBackNullAndNotEmpty() {
        when(currentRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer user-token");
        server.expect(requestTo(BASE_URL + "/api/v1/claims/7/rule-results"))
                .andExpect(method(GET))
                .andRespond(withServerError());

        assertThat(client.ruleResultsOf(7L)).isNull();
    }

    @Test
    void ruleResults_whenNoRuleRan_comeBackEmpty() {
        when(currentRequest.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer user-token");
        server.expect(requestTo(BASE_URL + "/api/v1/claims/7/rule-results"))
                .andExpect(method(GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.ruleResultsOf(7L)).isEmpty();
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
