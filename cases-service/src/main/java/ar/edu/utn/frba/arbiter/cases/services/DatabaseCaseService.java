package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseEntity;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class DatabaseCaseService implements CaseService {

    private final CaseRepository caseRepository;
    private final ClaimsAnalysisClient claimsAnalysisClient;
    private final RestClient.Builder restClientBuilder;

    @Value("${arbiter.classification-service.url:http://classification-service:8082}")
    private String classificationServiceUrl;

    @Override
    public CaseResponse createCase(CaseRequest request, Map<String, MultipartFile> documents) {
        CaseEntity entity = CaseEntity.builder()
                .branch(request.branch())
                .product(request.product())
                .claimCause(request.claimCause())
                .insuredItem(request.insuredItem())
                .insuredId(request.insuredId())
                .policyNumber(request.policyNumber())
                .description(request.description())
                .eventDate(request.eventDate())
                .eventLocation(request.eventLocation())
                .status("PENDING_CLASSIFICATION")
                .build();

        CaseEntity saved = caseRepository.save(entity);
        claimsAnalysisClient.analyzeAndPersist(saved, documents);

        return toResponse(saved);
    }

    @Override
    public CaseResponse getCase(Long caseId) {
        CaseEntity entity = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case " + caseId + " not found"));
        claimsAnalysisClient.refreshClassification(entity);
        return toResponse(entity);
    }

    @Override
    public CaseResponse addDocumentsAndReclassify(Long caseId, Map<String, MultipartFile> documents) {
        return getCase(caseId);
    }

    @Override
    public void recordAnalystDecision(Long caseId, AnalystDecisionRequest request) {
        CaseEntity entity = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case " + caseId + " not found"));

        if (entity.getClassificationClaimId() == null) {
            claimsAnalysisClient.analyzeAndPersist(entity, Map.of());
            caseRepository.save(entity);
        }

        restClientBuilder.build()
                .post()
                .uri(classificationServiceUrl + "/api/v1/claims/" + entity.getClassificationClaimId() + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    private CaseResponse toResponse(CaseEntity entity) {
        return new CaseResponse(
                entity.getId(),
                entity.getStatus(),
                entity.getBranch(),
                entity.getProduct(),
                entity.getClaimCause(),
                entity.getInsuredItem(),
                entity.getInsuredId(),
                entity.getPolicyNumber(),
                entity.getDescription(),
                entity.getEventDate(),
                entity.getEventLocation(),
                null,
                entity.getAnalysisClassification(),
                entity.getAnalysisConfidence() != null ? entity.getAnalysisConfidence() : 0.0,
                entity.getAnalysisDetail()
        );
    }
}
