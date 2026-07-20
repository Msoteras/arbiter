package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.dto.ClaimResponse;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.SecretKey;
import java.util.List;

@Component
public class ClassificationServiceClient implements ClaimsAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(ClassificationServiceClient.class);

    private final RestClient restClient;
    private final CaseStatusService caseStatusService;
    private final HttpServletRequest currentRequest;
    private final SecretKey jwtKey;

    public ClassificationServiceClient(
            RestClient.Builder restClientBuilder,
            CaseStatusService caseStatusService,
            @Value("${arbiter.classification-service.url:http://classification-service:8082}") String classificationServiceUrl,
            HttpServletRequest currentRequest,
            @Value("${arbiter.auth.jwt.secret}") String jwtSecret
    ) {
        this.restClient = restClientBuilder.baseUrl(classificationServiceUrl).build();
        this.caseStatusService = caseStatusService;
        this.currentRequest = currentRequest;
        this.jwtKey = JwtSupport.key(jwtSecret);
    }

    /**
     * classification-service now requires auth (H0003) — these two calls happen inside the same
     * request thread as the user's original call to cases-service, so we just forward their JWT
     * as-is instead of minting a new one (they already passed @PreAuthorize here with it).
     */
    private String authorizationHeader() {
        return currentRequest.getHeader(HttpHeaders.AUTHORIZATION);
    }

    @Override
    public AnalysisResult analyzeAndPersist(Case caseRecord, List<CaseDocument> documents) {
        ClaimReport claim = ClaimReport.builder()
                .branch(caseRecord.getBranch())
                .product(caseRecord.getProduct())
                .claimCause(caseRecord.getClaimCause())
                .insuredItem(caseRecord.getInsuredItem())
                .insuredId(caseRecord.getInsuredId())
                .policyNumber(caseRecord.getPolicyNumber())
                .description(caseRecord.getDescription())
                .eventDate(caseRecord.getEventDate())
                .eventLocation(caseRecord.getEventLocation())
                .claimedAmount(caseRecord.getClaimedAmount())
                .attachmentsOcr(List.of())
                .build();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("caseId", String.valueOf(caseRecord.getId()));
        body.add("claim", claim);
        documents.forEach(document -> body.add(document.getType(), toResource(document)));

        restClient.post()
                .uri("/api/v1/claims")
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .toBodilessEntity();

        // The case is already PENDING_CLASSIFICATION (set by the caller); this only fires the request.
        return new AnalysisResult(null, 0.0, "Classification in progress");
    }

    private ByteArrayResource toResource(CaseDocument document) {
        return new ByteArrayResource(document.getContent()) {
            @Override
            public String getFilename() {
                return document.getFilename();
            }
        };
    }

    /**
     * Single, non-blocking attempt to pull the classification result. Returns true if the
     * case now has a classification (already had one, or one just arrived); false if it's
     * still pending. Repetition is the scheduler's job, not this method's — no sleep here.
     */
    @Override
    public boolean refreshClassification(Case caseRecord) {
        if (caseRecord.getAnalysisClassification() != null) {
            return true;
        }

        try {
            // Called from ClassificationRefreshScheduler (@Scheduled, no HTTP request behind it) —
            // there's no user JWT to forward here, so we sign a short-lived service token instead.
            String serviceToken = JwtSupport.issueServiceToken(jwtKey, "cases-service-scheduler");
            ClaimResponse response = restClient.get()
                    .uri("/api/v1/claims/{caseId}", caseRecord.getId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                    .retrieve()
                    .body(ClaimResponse.class);

            if (response != null && response.classification() != null) {
                caseRecord.setAnalysisClassification(response.classification());
                caseRecord.setAnalysisConfidence(response.confidence());
                caseRecord.setAnalysisDetail(buildDetail(response));
                caseRecord.setDeterministicFastTrack(response.deterministicFastTrack());
                // Cache the parallel risk score. Null when "sin scorear" (no config) — kept null,
                // never coerced to a band, so the read model can show "Sin datos".
                caseRecord.setRiskScore(response.riskScore());
                caseRecord.setRiskBand(response.riskBand());
                caseRecord.setRiskBreakdown(response.riskBreakdown());
                caseStatusService.transition(caseRecord, statusFor(response.classification()),
                        StatusChangeActor.SYSTEM, "clasificación: " + response.classification());
                return true;
            }
        } catch (RestClientResponseException exception) {
            log.warn("Classification polling failed for case {}: {}", caseRecord.getId(), exception.getMessage());
        }

        return false;
    }

    /**
     * Missing documentation is the insured's turn (they upload what's missing and the case
     * re-enters classification); every other result goes to the analyst's queue.
     */
    private CaseStatus statusFor(Classification classification) {
        return classification == Classification.FALTA_DOCUMENTACION
                ? CaseStatus.AWAITING_DOCUMENTATION
                : CaseStatus.PENDING_ANALYST_REVIEW;
    }

    @Override
    public void forwardAnalystDecision(Long caseId, AnalystDecisionRequest request) {
        restClient.post()
                .uri("/api/v1/claims/{caseId}/decision", caseId)
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    private String buildDetail(ClaimResponse response) {
        List<String> factors = response.factors();
        if (factors != null && !factors.isEmpty()) {
            return String.join(", ", factors);
        }
        return response.deterministicFastTrack()
                ? "Fast track classification available"
                : "Classification completed";
    }
}
