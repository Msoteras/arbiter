package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Profile("dev")
@RequiredArgsConstructor
public class MockClaimsAnalysisClient implements ClaimsAnalysisClient {

    private final RestClient.Builder restClientBuilder;

    @Value("${arbiter.classification-service.url:http://classification-service:8082}")
    private String classificationServiceUrl;

    @Override
    public AnalysisResult analyze(CaseRequest request) {
        return new AnalysisResult(null, 0.0, "Mock classification pending");
    }

    @Override
    public AnalysisResult analyzeAndPersist(CaseEntity caseEntity, Map<String, MultipartFile> documents) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        Map<String, Object> claimPayload = new LinkedHashMap<>();
        claimPayload.put("branch", caseEntity.getBranch());
        claimPayload.put("product", caseEntity.getProduct());
        claimPayload.put("claimCause", caseEntity.getClaimCause());
        claimPayload.put("insuredItem", caseEntity.getInsuredItem());
        claimPayload.put("insuredId", caseEntity.getInsuredId());
        claimPayload.put("policyNumber", caseEntity.getPolicyNumber());
        claimPayload.put("description", caseEntity.getDescription());
        claimPayload.put("eventDate", caseEntity.getEventDate());
        claimPayload.put("eventLocation", caseEntity.getEventLocation());
        claimPayload.put("claimedAmount", null);
        claimPayload.put("attachmentsOcr", List.of());
        body.add("claim", claimPayload);

        Map<String, Object> response = restClientBuilder.build()
                .post()
                .uri(classificationServiceUrl + "/api/v1/claims")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(Map.class);

        Long claimId = response != null && response.containsKey("claimId")
                ? ((Number) response.get("claimId")).longValue()
                : null;
        caseEntity.setClassificationClaimId(claimId);
        caseEntity.setStatus("PENDING_CLASSIFICATION");
        return new AnalysisResult(null, 0.0, "Mock classification in progress");
    }

    @Override
    public boolean refreshClassification(CaseEntity caseEntity) {
        return false;
    }
}
