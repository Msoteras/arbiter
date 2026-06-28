package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseEntity;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class InMemoryCaseService implements CaseService {

    private final CaseRepository caseRepository;
    private final RealClaimsAnalysisClient realClaimsAnalysisClient;

    @Override
    public CaseResponse createCase(CaseRequest request) {
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
        realClaimsAnalysisClient.analyzeAndPersist(saved);

        return toResponse(saved);
    }

    @Override
    public CaseResponse getCase(Long caseId) {
        CaseEntity entity = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case " + caseId + " not found"));
        realClaimsAnalysisClient.refreshClassification(entity);
        return toResponse(entity);
    }

    private CaseResponse toResponse(CaseEntity entity) {
        return new CaseResponse(
                entity.getId(),
                entity.getStatus(),
                entity.getPolicyNumber(),
                entity.getInsuredId(),
                entity.getAnalysisClassification(),
                entity.getAnalysisConfidence() != null ? entity.getAnalysisConfidence() : 0.0,
                entity.getAnalysisDetail()
        );
    }
}
