package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseEntity;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.dto.ClaimResponse;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

@Component
public class ClassificationServiceClient implements ClaimsAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(ClassificationServiceClient.class);

    private final RestClient restClient;
    private final CaseRepository caseRepository;

    public ClassificationServiceClient(
            RestClient.Builder restClientBuilder,
            CaseRepository caseRepository,
            @Value("${arbiter.classification-service.url:http://classification-service:8082}") String classificationServiceUrl
    ) {
        this.restClient = restClientBuilder.baseUrl(classificationServiceUrl).build();
        this.caseRepository = caseRepository;
    }

    @Override
    public AnalysisResult analyzeAndPersist(CaseEntity caseEntity, List<CaseDocument> documents) {
        ClaimReport claim = ClaimReport.builder()
                .branch(caseEntity.getBranch())
                .product(caseEntity.getProduct())
                .claimCause(caseEntity.getClaimCause())
                .insuredItem(caseEntity.getInsuredItem())
                .insuredId(caseEntity.getInsuredId())
                .policyNumber(caseEntity.getPolicyNumber())
                .description(caseEntity.getDescription())
                .eventDate(caseEntity.getEventDate())
                .eventLocation(caseEntity.getEventLocation())
                .claimedAmount(caseEntity.getClaimedAmount())
                .attachmentsOcr(List.of())
                .build();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("caseId", String.valueOf(caseEntity.getId()));
        body.add("claim", claim);
        documents.forEach(document -> body.add(document.getType(), toResource(document)));

        restClient.post()
                .uri("/api/v1/claims")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .toBodilessEntity();

        caseEntity.setStatus(CaseStatus.PENDING_CLASSIFICATION);
        caseRepository.save(caseEntity);

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
    public boolean refreshClassification(CaseEntity caseEntity) {
        if (caseEntity.getAnalysisClassification() != null) {
            return true;
        }

        try {
            ClaimResponse response = restClient.get()
                    .uri("/api/v1/claims/{caseId}", caseEntity.getId())
                    .retrieve()
                    .body(ClaimResponse.class);

            if (response != null && response.classification() != null) {
                caseEntity.setAnalysisClassification(response.classification());
                caseEntity.setAnalysisConfidence(response.confidence());
                caseEntity.setAnalysisDetail(buildDetail(response));
                caseEntity.setDeterministicFastTrack(response.deterministicFastTrack());
                caseEntity.setStatus(CaseStatus.PENDING_ANALYST_REVIEW);
                caseRepository.save(caseEntity);
                return true;
            }
        } catch (RestClientResponseException exception) {
            log.warn("Classification polling failed for case {}: {}", caseEntity.getId(), exception.getMessage());
        }

        return false;
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
