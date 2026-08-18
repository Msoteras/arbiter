package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.dto.ClaimResponse;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordRequest;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordResponse;
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
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.SecretKey;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
     * classification-service now requires auth (H0003) — these calls happen inside the same
     * request thread as the user's original call to cases-service, so we just forward their JWT
     * as-is instead of minting a new one (they already passed @PreAuthorize here with it).
     */
    private String authorizationHeader() {
        return currentRequest.getHeader(HttpHeaders.AUTHORIZATION);
    }

    /**
     * El token del usuario lleva el {@code tenantSchema} que se resolvió en el login, y el alta de
     * una denuncia puede correr en otro: el de la aseguradora que emitió la póliza. Reenviarlo tal
     * cual haría que classification-service escriba el análisis en el esquema equivocado, así que
     * cuando la operación se movió de tenant se firma un token de servicio con el tenant real —
     * mismo mecanismo que usa el scheduler, que tampoco tiene un JWT de usuario con el tenant que
     * necesita. Si no se movió, se reenvía el del usuario y la cadena de identidad queda intacta.
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
        // The contract with classification-service is unchanged: still plain strings, only now
        // read off the joins instead of off denormalized columns.
        ClaimReport claim = ClaimReport.builder()
                .branch(caseRecord.getClaimCause().getBranch().getName())
                .product(caseRecord.getPolicy().getProduct())
                .claimCause(caseRecord.getClaimCause().getName())
                .coverageId(caseRecord.getCoverage().getId())
                .claimCauseId(caseRecord.getClaimCause().getId())
                .insuredItem(caseRecord.getDeclaredItem())
                .insuredId(caseRecord.getInsured().getDni())
                .policyNumber(caseRecord.getPolicy().getExternalPolicyNumber())
                .description(caseRecord.getDescription())
                .eventDate(caseRecord.getOccurredAt())
                .eventLocation(caseRecord.getEventAddress())
                .claimedAmount(caseRecord.getClaimedAmount())
                // reportedAt (Instant) → LocalDateTime para el motor: la regla del plazo de denuncia
                // (D11) compara reportedAt - occurredAt contra el plazo de la cobertura.
                .reportedAt(caseRecord.getReportedAt() == null ? null
                        : LocalDateTime.ofInstant(caseRecord.getReportedAt(), ZoneId.systemDefault()))
                // Lo que el asegurado declaró en el wizard sobre su denuncia policial (D12). Se
                // capturaba desde el 09/08 y se quedaba en cases-service: sin esto el motor no
                // podía evaluar el plazo ni cruzarlo contra la fecha que dice la constancia.
                .policeReportAt(caseRecord.getPoliceReportAt())
                .attachmentsOcr(List.of())
                .build();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("caseId", String.valueOf(caseRecord.getId()));
        body.add("claim", claim);
        documents.forEach(document -> body.add(document.getType(), toResource(document)));
        // Keyed by type, matching the file parts above: case_documents is unique on
        // (case_id, type), so the type pairs each id with its file. Sent as one JSON part
        // rather than loose params — a @RequestParam Map would swallow every other param on
        // the request. classification-service persists these on the image analysis so a
        // duplicate match can name the exact document it matched.
        body.add("documentIds", documents.stream()
                .collect(Collectors.toMap(CaseDocument::getType, CaseDocument::getId)));

        restClient.post()
                .uri("/api/v1/claims")
                .header(HttpHeaders.AUTHORIZATION, authorizationHeaderForCurrentTenant())
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
        // La clasificación ya no se cachea en `cases`, así que el estado es la señal: el poller
        // solo trae PENDING_CLASSIFICATION, y salir de ese estado es exactamente lo que hace este
        // método cuando el resultado llega. Cualquier otro estado significa que ya se resolvió.
        if (caseRecord.getStatus() != CaseStatus.PENDING_CLASSIFICATION) {
            return true;
        }

        try {
            // Called from ClassificationRefreshScheduler (@Scheduled, no HTTP request behind it) —
            // there's no user JWT to forward here, so we sign a short-lived service token instead.
            // It carries the tenant the scheduler is currently sweeping: classification-service
            // resolves its own schema from that claim, and without it would read the common one.
            String serviceToken = JwtSupport.issueServiceToken(
                    jwtKey, "cases-service-scheduler", TenantContext.get());
            ClaimResponse response = restClient.get()
                    .uri("/api/v1/claims/{caseId}", caseRecord.getId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                    .retrieve()
                    .body(ClaimResponse.class);

            if (response != null && response.classification() != null) {
                // La recomendación, su confianza y sus motivos NO se copian: viven en llm_analysis,
                // en este mismo esquema, y CaseAnalysisRepository los joinea al armar la respuesta.
                // Acá solo queda lo que la bandeja filtra, más lo que no tiene otra tabla de dónde
                // salir (was_fast_track, forensic_report).
                caseRecord.setDeterministicFastTrack(response.deterministicFastTrack());
                // Cache the parallel risk score. Null when "sin scorear" (no config) — kept null,
                // never coerced to a band, so the read model can show "Sin datos".
                caseRecord.setRiskScore(response.riskScore());
                caseRecord.setRiskBand(response.riskBand());
                // insuredName is no longer cached off the poll: the case joins `insured` directly,
                // so the name is always there instead of appearing with the first classification.
                // Cache the structured image-fraud analysis for the analyst's forensic tab
                // (H0009). Null when no analysis ran (Fast Track, or a case with no images).
                caseRecord.setForensicReport(response.forensicReport());
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
    public Long forwardAnalystDecision(Long caseId, AnalystDecisionRequest request) {
        Map<String, Object> response = restClient.post()
                .uri("/api/v1/claims/{caseId}/decision", caseId)
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader())
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

    /**
     * Errors are not swallowed: an empty list would read as "este asegurado no tiene antecedentes",
     * which is the one wrong answer to give an analyst deciding a claim. Let it surface.
     */
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

}
