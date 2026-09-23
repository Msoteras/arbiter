package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseStatusHistory;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseStatusHistoryRepository;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.dto.ClaimResponse;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordRequest;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordResponse;
import ar.edu.utn.frba.arbiter.common.dto.PriorClaim;
import ar.edu.utn.frba.arbiter.common.dto.RuleResultResponse;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class ClassificationServiceClient implements ClaimsAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(ClassificationServiceClient.class);

    private final RestClient restClient;
    private final CaseStatusService caseStatusService;
    private final CaseRepository caseRepository;
    private final CaseStatusHistoryRepository caseStatusHistoryRepository;
    private final HttpServletRequest currentRequest;
    private final SecretKey jwtKey;

    public ClassificationServiceClient(
            RestClient.Builder restClientBuilder,
            CaseStatusService caseStatusService,
            CaseRepository caseRepository,
            CaseStatusHistoryRepository caseStatusHistoryRepository,
            @Value("${arbiter.classification-service.url:http://classification-service:8082}") String classificationServiceUrl,
            HttpServletRequest currentRequest,
            @Value("${arbiter.auth.jwt.secret}") String jwtSecret
    ) {
        this.restClient = restClientBuilder.baseUrl(classificationServiceUrl).build();
        this.caseStatusService = caseStatusService;
        this.caseRepository = caseRepository;
        this.caseStatusHistoryRepository = caseStatusHistoryRepository;
        this.currentRequest = currentRequest;
        this.jwtKey = JwtSupport.key(jwtSecret);
    }

    /** Forwards the caller's JWT as-is: these calls run on the user's request thread. */
    private String authorizationHeader() {
        return currentRequest.getHeader(HttpHeaders.AUTHORIZATION);
    }

    /**
     * The user's token carries the tenant resolved at login, but filing may run under the policy's
     * issuing insurer. Forwarding it would make classification-service write to the wrong schema,
     * so when the tenant moved a service token for the actual tenant is signed instead.
     */
    private String authorizationHeaderForCurrentTenant() {
        if (!CallerContext.get().movedAwayFromHome()) {
            return authorizationHeader();
        }
        return "Bearer " + JwtSupport.issueServiceToken(
                jwtKey, "cases-service-alta", TenantContext.get());
    }

    @Override
    public AnalysisResult analyzeAndPersist(Case caseRecord, List<CaseDocument> documents) {
        postClassify(caseRecord, documents, authorizationHeaderForCurrentTenant());
        return new AnalysisResult(null, 0.0, "Classification in progress");
    }

    /**
     * For callers with no request in scope, where touching {@code currentRequest} would throw
     * "No thread-bound request found": always signs a service token.
     */
    @Override
    public AnalysisResult analyzeAndPersistAsSystem(Case caseRecord, List<CaseDocument> documents) {
        String serviceToken = JwtSupport.issueServiceToken(
                jwtKey, "cases-service-recovery", TenantContext.get());
        postClassify(caseRecord, documents, "Bearer " + serviceToken);
        return new AnalysisResult(null, 0.0, "Classification in progress");
    }

    /**
     * The insured's other cases filed through Arbiter, in the shape the engine already uses for the
     * insurer's claim history, so rules count both sources alike. Read on every run, not cached:
     * hours may pass between filing and a retry.
     *
     * <p>Not filtered by status, matching the insurer's history; rules that care filter themselves.
     * No settled amount: payment happens outside the platform, and leaving it null keeps an
     * approved case from consuming the coverage until the insurer actually pays.
     */
    private List<PriorClaim> antecedentsOf(Case caseRecord) {
        List<PriorClaim> antecedents = caseRepository
                .findAntecedentsOf(caseRecord.getInsured().getDni(), caseRecord.getId())
                .stream()
                .map(prior -> PriorClaim.builder()
                        .caseId(prior.getId())
                        .eventDate(prior.getOccurredAt() == null ? null : prior.getOccurredAt().toLocalDate())
                        .policyNumber(prior.getPolicy().getExternalPolicyNumber())
                        .branch(prior.getClaimCause().getBranch().getName())
                        .coverageName(prior.getCoverage() == null ? null : prior.getCoverage().getName())
                        .claimCause(prior.getClaimCause().getName())
                        .status(prior.getStatus().name())
                        .build())
                .toList();
        log.info("[ClaimsAnalysis] {} antecedent(s) in Arbiter for case {}", antecedents.size(), caseRecord.getId());
        return antecedents;
    }

    private void postClassify(Case caseRecord, List<CaseDocument> documents, String authorizationHeader) {
        ClaimReport claim = ClaimReport.builder()
                .branch(caseRecord.getClaimCause().getBranch().getName())
                .product(caseRecord.getPolicy().getProduct())
                .claimCause(caseRecord.getClaimCause().getName())
                .coverageId(caseRecord.getCoverage().getId())
                .coverageName(caseRecord.getCoverage().getName())
                .claimCauseId(caseRecord.getClaimCause().getId())
                .insuredItem(caseRecord.getDeclaredItem())
                .insuredId(caseRecord.getInsured().getDni())
                .policyNumber(caseRecord.getPolicy().getExternalPolicyNumber())
                .description(caseRecord.getDescription())
                .eventDate(caseRecord.getOccurredAt())
                .eventLocation(caseRecord.getEventAddress())
                .claimedAmount(caseRecord.getClaimedAmount())
                .reportedAt(caseRecord.getReportedAt() == null ? null
                        : LocalDateTime.ofInstant(caseRecord.getReportedAt(), ZoneId.systemDefault()))
                .policeReportAt(caseRecord.getPoliceReportAt())
                .imageConsent(caseRecord.getInsured().isImageConsent())
                .attachmentsOcr(List.of())
                // The insurer's history only holds what it settled itself; claims filed here never
                // flow back, so without these the annual cap and Fast Track's prior-claims check read zero.
                .priorClaims(antecedentsOf(caseRecord))
                .build();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("caseId", String.valueOf(caseRecord.getId()));
        body.add("claim", claim);
        documents.forEach(document -> body.add(document.getType(), toResource(document)));
        // Keyed by type, which is unique per case, so each id pairs with its file part. One JSON
        // part rather than loose params: a @RequestParam Map would swallow every other param.
        body.add("documentIds", documents.stream()
                .collect(Collectors.toMap(CaseDocument::getType, CaseDocument::getId)));

        restClient.post()
                .uri("/api/v1/claims")
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private ByteArrayResource toResource(CaseDocument document) {
        return new ByteArrayResource(document.getContent()) {
            @Override
            public String getFilename() {
                return document.getFilename();
            }
        };
    }

    /** No sleeping here: repetition is the scheduler's job. */
    @Override
    public boolean refreshClassification(Case caseRecord) {
        // The status is the signal: leaving PENDING_CLASSIFICATION is what a resolved result does.
        if (caseRecord.getStatus() != CaseStatus.PENDING_CLASSIFICATION) {
            return true;
        }

        try {
            // No user JWT behind the scheduler. The token carries the tenant being swept, which
            // classification-service resolves its schema from.
            String serviceToken = JwtSupport.issueServiceToken(
                    jwtKey, "cases-service-scheduler", TenantContext.get());
            ClaimResponse response = restClient.get()
                    .uri("/api/v1/claims/{caseId}", caseRecord.getId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                    .retrieve()
                    .body(ClaimResponse.class);

            if (response != null && response.classification() != null && isStale(caseRecord, response)) {
                // A previous round's result while the new run is still going: treat it as not ready.
                log.debug("Case {} classification poll returned a result from before this round "
                                + "(analyzed {}); still waiting for a fresh one", caseRecord.getId(),
                        response.analyzedAt());
                return false;
            }

            if (response != null && response.classification() != null) {
                // Move the status first, with a compare-and-set: the guard above read the sweep's
                // stale copy, and another instance may be polling the same case.
                Optional<Case> claimed = caseStatusService.transitionIfStillIn(
                        caseRecord, CaseStatus.PENDING_CLASSIFICATION,
                        statusFor(response.classification()), StatusChangeActor.SYSTEM,
                        "clasificación: " + response.classification());
                if (claimed.isEmpty()) {
                    log.debug("Case {} already resolved by another sweep, skipping", caseRecord.getId());
                    return true;
                }

                // Saved on the entity re-read after the CAS; saving the stale copy would rewrite the row.
                Case resolved = claimed.get();
                // The recommendation itself isn't copied: it is joined from llm_analysis on read.
                // Only what the inbox filters on, or has no other table, is cached here.
                resolved.setDeterministicFastTrack(response.deterministicFastTrack());
                // Null when unscored; never coerced to a band.
                resolved.setRiskScore(response.riskScore());
                resolved.setRiskBand(response.riskBand());
                resolved.setForensicReport(response.forensicReport());
                caseRepository.save(resolved);
                return true;
            }
        } catch (RestClientResponseException exception) {
            log.warn("Classification polling failed for case {}: {}", caseRecord.getId(), exception.getMessage());
        }

        return false;
    }

    /**
     * {@code llm_analysis} is append-only, so while a new run is in flight classification-service
     * still answers with the previous round's row. A null {@code analyzedAt} means Fast Track, whose
     * result is rewritten on every run and so can't be stale.
     */
    private boolean isStale(Case caseRecord, ClaimResponse response) {
        if (response.analyzedAt() == null) {
            return false;
        }
        Instant enteredPendingAt = caseStatusHistoryRepository
                .findFirstByCaseIdAndFinalStatus_IdOrderByChangedAtDesc(
                        caseRecord.getId(), caseRecord.getCurrentStatus().getId())
                .map(CaseStatusHistory::getChangedAt)
                .orElse(caseRecord.getUpdatedAt());
        return response.analyzedAt().isBefore(enteredPendingAt);
    }

    /** Missing documentation is the insured's turn; every other result goes to the analyst. */
    private CaseStatus statusFor(Classification classification) {
        return classification == Classification.FALTA_DOCUMENTACION
                ? CaseStatus.AWAITING_DOCUMENTATION
                : CaseStatus.PENDING_ANALYST_REVIEW;
    }

    /** A service token, not the user's, so the endpoint isn't reachable directly by users. */
    @Override
    public Long forwardAnalystDecision(Long caseId, AnalystDecisionRequest request) {
        String serviceToken = JwtSupport.issueServiceToken(
                jwtKey, "cases-service-decision", TenantContext.get());
        Map<String, Object> response = restClient.post()
                .uri("/api/v1/claims/{caseId}/decision", caseId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        Object classificationId = response == null ? null : response.get("classificationId");
        return classificationId instanceof Number number ? number.longValue() : null;
    }

    @Override
    public FraudRecordResponse registerFraudRecord(FraudRecordRequest request) {
        return restClient.post()
                .uri("/api/v1/fraud-records")
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(FraudRecordResponse.class);
    }

    /** Errors propagate: an empty list would wrongly tell the analyst the insured has no record. */
    @Override
    public List<FraudRecordResponse> fraudRecordsOf(String insuredDni) {
        List<FraudRecordResponse> records = restClient.get()
                .uri("/api/v1/fraud-records/insured/{insuredDni}", insuredDni)
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return records == null ? List.of() : records;
    }

    /**
     * Degrades to {@code null} instead of failing the case detail: the traceability tab is context.
     * Not an empty list, which would mean "no rule ran".
     */
    @Override
    public List<RuleResultResponse> ruleResultsOf(Long caseId) {
        try {
            List<RuleResultResponse> results = restClient.get()
                    .uri("/api/v1/claims/{caseId}/rule-results", caseId)
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            // A 200 with no body is a genuine "none ran", not a read failure.
            return results == null ? List.of() : results;
        } catch (RestClientException e) {
            log.warn("[ClaimsAnalysis] Could not read rule results for case {}: {}", caseId, e.getMessage());
            return null;
        }
    }

}
