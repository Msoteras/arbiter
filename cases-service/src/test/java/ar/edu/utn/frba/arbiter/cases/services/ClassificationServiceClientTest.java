package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.cases.support.CaseStates;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordRequest;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationFailureReason;
import ar.edu.utn.frba.arbiter.common.enums.FraudRecordSource;
import ar.edu.utn.frba.arbiter.common.http.ConnectionRetryInterceptor;
import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mock.http.client.MockClientHttpRequest;
import ar.edu.utn.frba.arbiter.cases.models.entities.RequestedDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class ClassificationServiceClientTest {

    private static final String BASE_URL = "http://classification-service:8082";
    private static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-for-hs256";

    @Mock
    private CaseStatusService caseStatusService;

    @Mock
    private CaseRepository caseRepository;

    @Mock
    private AnalystNotificationService analystNotificationService;

    private MockRestServiceServer server;
    private ClassificationServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ClassificationServiceClient(builder,
                new ConnectionRetryInterceptor(1, Duration.ofMillis(1), Duration.ofMillis(1)), caseStatusService, caseRepository,
                analystNotificationService, BASE_URL, JWT_SECRET);
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

    /** The analyst chooses what the insured is asked for: the case waits for them, not for the insured. */
    @Test
    void missingDocumentation_goesToTheAnalystAndTellsThem() {
        Case entity = pendingCase(3L);
        winsTheTurn(entity, CaseStatus.PENDING_ANALYST_REVIEW);
        expectPoll(3L, "FALTA_DOCUMENTACION", "1.0",
                "[\"Falta documento requerido: police_report\"]", false);

        boolean resolved = client.refreshClassification(entity);

        assertThat(resolved).isTrue();
        verify(caseStatusService).transitionIfStillIn(eq(entity), eq(CaseStatus.PENDING_CLASSIFICATION),
                eq(CaseStatus.PENDING_ANALYST_REVIEW), eq(StatusChangeActor.SYSTEM), any());
        verify(analystNotificationService).notifyDocumentationToConfirm(entity);
    }

    /** Documents were asked for because the risk was high: a new one doesn't make it low. */
    @Test
    void aLowerScoreOnReclassification_keepsTheHigherBand() {
        Case entity = pendingCase(4L);
        entity.setRiskScore(0.85);
        entity.setRiskBand(RiskBand.CRITICAL);
        winsTheTurn(entity, CaseStatus.PENDING_ANALYST_REVIEW);
        server.expect(requestTo(BASE_URL + "/api/v1/claims/4"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {
                          "caseId": 4,
                          "classification": "LLM_RECOMIENDA_APROBAR",
                          "confidence": 0.8,
                          "factors": [],
                          "resolvedByRules": false,
                          "riskScore": 0.4,
                          "riskBand": "MEDIUM"
                        }
                        """, MediaType.APPLICATION_JSON));

        client.refreshClassification(entity);

        assertThat(entity.getRiskScore()).isEqualTo(0.85);
        assertThat(entity.getRiskBand()).isEqualTo(RiskBand.CRITICAL);
        verify(analystNotificationService, never()).notifyDocumentationToConfirm(any());
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
        assertThat(entity.getRulesClassification()).isEqualTo(Classification.FAST_TRACK);
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
                          "resolvedByRules": false,
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
        assertThat(entity.getRulesClassification()).isNull();
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
        when(caseStatusService.enteredCurrentStatusAt(entity)).thenReturn(enteredPendingClassificationAt);
        server.expect(requestTo(BASE_URL + "/api/v1/claims/23"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {
                          "caseId": 23,
                          "classification": "FALTA_DOCUMENTACION",
                          "confidence": 1.0,
                          "factors": ["Falta documento requerido: item_photo"],
                          "resolvedByRules": false,
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
        when(caseStatusService.enteredCurrentStatusAt(entity)).thenReturn(enteredPendingClassificationAt);
        server.expect(requestTo(BASE_URL + "/api/v1/claims/23"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {
                          "caseId": 23,
                          "classification": "LLM_NO_RECOMIENDA_APROBAR",
                          "confidence": 0.95,
                          "factors": ["Monto reclamado supera la suma asegurada"],
                          "resolvedByRules": false,
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
                        {"caseId": 7, "classification": null, "confidence": null, "factors": null, "resolvedByRules": false}
                        """, MediaType.APPLICATION_JSON));

        client.refreshClassification(entity);

        server.verify();
    }

    @Test
    void analyzeAndPersist_signsAServiceToken() {
        Case entity = pendingCase(9L);
        server.expect(requestTo(BASE_URL + "/api/v1/claims"))
                .andExpect(method(POST))
                .andExpect(request -> assertServiceToken(request, "cases-service-alta"))
                .andRespond(withSuccess());

        client.analyzeAndPersist(entity, List.<CaseDocument>of());

        server.verify();
    }

    /** The model reads what the analyst wrote, and the schedule isn't demanded again after a request. */
    @Test
    void analyzeAndPersist_sendsTheAnalystsNotesAndThatTheyAskedForDocuments() {
        Case entity = pendingCase(11L);
        entity.setRequestedDocuments(List.of(new RequestedDocument(
                "other_1", "Resumen de la tarjeta", "Confirmar la fecha de compra", true, null)));
        server.expect(requestTo(BASE_URL + "/api/v1/claims"))
                .andExpect(method(POST))
                .andExpect(request -> assertThat(((MockClientHttpRequest) request).getBodyAsString())
                        .contains("\"analystDocumentNotes\":[{\"type\":\"other_1\"",
                                "\"description\":\"Resumen de la tarjeta\"",
                                "\"reason\":\"Confirmar la fecha de compra\"",
                                "\"documentationRequestedByAnalyst\":true"))
                .andRespond(withSuccess());

        client.analyzeAndPersist(entity, List.<CaseDocument>of());

        server.verify();
    }

    @Test
    void analyzeAndPersistAsSystem_signsAsTheRecoverySweep() {
        Case entity = pendingCase(10L);
        server.expect(requestTo(BASE_URL + "/api/v1/claims"))
                .andExpect(method(POST))
                .andExpect(request -> assertServiceToken(request, "cases-service-recovery"))
                .andRespond(withSuccess());

        client.analyzeAndPersistAsSystem(entity, List.<CaseDocument>of());

        server.verify();
    }

    @Test
    void registerFraudRecord_signsAServiceToken() {
        server.expect(requestTo(BASE_URL + "/api/v1/fraud-records"))
                .andExpect(method(POST))
                .andExpect(request -> assertServiceToken(request, "cases-service-fraud-record"))
                .andRespond(withSuccess());

        client.registerFraudRecord(new FraudRecordRequest("40.123.456", 9L,
                FraudRecordSource.ANALYST_DECLARED, "Documentación adulterada", null, 1L, "Lucas Pérez"));

        server.verify();
    }

    @Test
    void fraudRecordsOf_signsAServiceToken() {
        server.expect(requestTo(BASE_URL + "/api/v1/fraud-records/insured/40.123.456"))
                .andExpect(method(GET))
                .andExpect(request -> assertServiceToken(request, "cases-service-fraud-record"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.fraudRecordsOf("40.123.456");

        server.verify();
    }

    @Test
    void ruleResultsOf_signsAServiceToken() {
        server.expect(requestTo(BASE_URL + "/api/v1/claims/7/rule-results"))
                .andExpect(method(GET))
                .andExpect(request -> assertServiceToken(request, "cases-service-rule-results"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.ruleResultsOf(7L);

        server.verify();
    }

    private static void assertServiceToken(ClientHttpRequest request, String subject) {
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        assertThat(authHeader).startsWith("Bearer ");
        var claims = io.jsonwebtoken.Jwts.parser()
                .verifyWith(JwtSupport.key(JWT_SECRET)).build()
                .parseSignedClaims(authHeader.substring(7))
                .getPayload();
        assertThat(claims.getSubject()).isEqualTo(subject);
        assertThat(claims.get("rol")).isNull();
    }

    @Test
    void analyzeAndPersist_whenClassificationCantBeReached_leavesTheCaseToTheRecoverySweep() {
        Case entity = pendingCase(11L);
        server.expect(ExpectedCount.times(2), requestTo(BASE_URL + "/api/v1/claims"))
                .andRespond(withException(new ConnectException("Connection refused")));

        client.analyzeAndPersist(entity, List.<CaseDocument>of());

        assertThat(entity.getClassificationFailureReason()).isEqualTo(ClassificationFailureReason.INFRASTRUCTURE);
        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.CLASSIFICATION_FAILED),
                eq(StatusChangeActor.SYSTEM), any());
    }

    @Test
    void analyzeAndPersist_whenClassificationIsStillBooting_resendsTheClaim() {
        Case entity = pendingCase(14L);
        server.expect(requestTo(BASE_URL + "/api/v1/claims"))
                .andRespond(withException(new ConnectException("Connection refused")));
        server.expect(requestTo(BASE_URL + "/api/v1/claims")).andExpect(method(POST)).andRespond(withSuccess());

        client.analyzeAndPersist(entity, List.<CaseDocument>of());

        server.verify();
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void analyzeAndPersist_whenClassificationFailsWith5xx_alsoLeavesTheCaseToTheRecoverySweep() {
        Case entity = pendingCase(12L);
        server.expect(requestTo(BASE_URL + "/api/v1/claims")).andRespond(withServerError());

        client.analyzeAndPersist(entity, List.<CaseDocument>of());

        assertThat(entity.getClassificationFailureReason()).isEqualTo(ClassificationFailureReason.INFRASTRUCTURE);
        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.CLASSIFICATION_FAILED),
                eq(StatusChangeActor.SYSTEM), any());
    }

    @Test
    void analyzeAndPersist_whenClassificationRejectsTheRequest_propagatesWithoutTouchingTheCase() {
        Case entity = pendingCase(13L);
        server.expect(requestTo(BASE_URL + "/api/v1/claims")).andRespond(withBadRequest());

        assertThatThrownBy(() -> client.analyzeAndPersist(entity, List.<CaseDocument>of()))
                .isInstanceOf(HttpClientErrorException.class);
        assertThat(entity.getClassificationFailureReason()).isNull();
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void isReachable_whenTheHealthCheckAnswers() {
        server.expect(requestTo(BASE_URL + "/actuator/health")).andRespond(withSuccess());

        assertThat(client.isReachable()).isTrue();
    }

    @Test
    void isReachable_whenTheConnectionFails() {
        server.expect(requestTo(BASE_URL + "/actuator/health"))
                .andRespond(withException(new ConnectException("Connection refused")));

        assertThat(client.isReachable()).isFalse();
    }

    @Test
    void forwardAnalystDecision_signsAServiceTokenInsteadOfForwardingTheUsers() {
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
        server.expect(requestTo(BASE_URL + "/api/v1/claims/7/rule-results"))
                .andExpect(method(GET))
                .andRespond(withServerError());

        assertThat(client.ruleResultsOf(7L)).isNull();
    }

    @Test
    void ruleResults_whenNoRuleRan_comeBackEmpty() {
        server.expect(requestTo(BASE_URL + "/api/v1/claims/7/rule-results"))
                .andExpect(method(GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.ruleResultsOf(7L)).isEmpty();
    }

    private void expectPoll(long caseId, String classification, String confidence,
                            String factors, boolean resolvedByRules) {
        server.expect(requestTo(BASE_URL + "/api/v1/claims/" + caseId))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {
                          "caseId": %d,
                          "classification": %s,
                          "confidence": %s,
                          "factors": %s,
                          "resolvedByRules": %b
                        }
                        """.formatted(caseId,
                                quoteOrNull(classification), confidence, factors, resolvedByRules),
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
