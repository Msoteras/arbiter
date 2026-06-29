package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.CaseEntity;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class RealClaimsAnalysisClient implements ClaimsAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(RealClaimsAnalysisClient.class);
    private static final int MAX_POLL_ATTEMPTS = 6;
    private static final long POLL_DELAY_MS = 1_000L;

    private final RestClient.Builder restClientBuilder;
    private final CaseRepository caseRepository;

    @Value("${arbiter.classification-service.url:http://classification-service:8082}")
    private String classificationServiceUrl;

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
        claimPayload.put("claimedAmount", caseEntity.getClaimedAmount());
        claimPayload.put("attachmentsOcr", List.of());
        body.add("claim", claimPayload);

        if (documents != null) {
            documents.forEach((type, file) -> body.add(type, toResource(type, file)));
        }

        Map<String, Object> response = restClientBuilder.build()
                .post()
                .uri(classificationServiceUrl + "/api/v1/claims")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(Map.class);

        Long claimId = response.containsKey("claimId") ? ((Number) response.get("claimId")).longValue() : null;
        caseEntity.setClassificationClaimId(claimId);
        caseEntity.setStatus("PENDING_CLASSIFICATION");
        caseRepository.save(caseEntity);

        return new AnalysisResult(null, 0.0, "Classification in progress");
    }

    private ByteArrayResource toResource(String type, MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Could not read document '" + type + "'", e);
        }
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
        };
    }

    public boolean refreshClassification(CaseEntity caseEntity) {
        if (caseEntity.getClassificationClaimId() == null) {
            return false;
        }

        for (int attempt = 1; attempt <= MAX_POLL_ATTEMPTS; attempt++) {
            try {
                Map<String, Object> response = restClientBuilder.build()
                        .get()
                        .uri(classificationServiceUrl + "/api/v1/claims/" + caseEntity.getClassificationClaimId())
                        .retrieve()
                        .body(Map.class);

                Object classificationValue = response.get("classification");
                if (classificationValue instanceof String classificationText && !classificationText.isBlank()) {
                    caseEntity.setAnalysisClassification(classificationText);
                    caseEntity.setAnalysisConfidence(extractDouble(response.get("confidence")));
                    caseEntity.setAnalysisDetail(buildDetail(response));
                    caseEntity.setDeterministicFastTrack(Boolean.TRUE.equals(response.get("deterministicFastTrack")));
                    caseEntity.setStatus("CLASSIFIED");
                    caseRepository.save(caseEntity);
                    return true;
                }
            } catch (RestClientResponseException exception) {
                log.warn("Classification polling failed for case {} at attempt {}/{}: {}",
                        caseEntity.getId(), attempt, MAX_POLL_ATTEMPTS, exception.getMessage());
            }

            if (attempt < MAX_POLL_ATTEMPTS) {
                try {
                    Thread.sleep(POLL_DELAY_MS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return false;
    }

    private String buildDetail(Map<String, Object> response) {
        Object factorsValue = response.get("factors");
        if (factorsValue instanceof List<?> factorsList) {
            List<String> factors = new ArrayList<>();
            for (Object factor : factorsList) {
                if (factor instanceof String factorText) {
                    factors.add(factorText);
                }
            }
            if (!factors.isEmpty()) {
                return String.join(", ", factors);
            }
        }
        return Boolean.TRUE.equals(response.get("deterministicFastTrack"))
                ? "Fast track classification available"
                : "Classification completed";
    }

    private Double extractDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }
}
