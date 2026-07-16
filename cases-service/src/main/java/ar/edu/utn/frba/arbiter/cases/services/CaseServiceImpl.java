package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.DocumentReadException;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CaseServiceImpl implements CaseService {

    private final CaseRepository caseRepository;
    private final CaseDocumentRepository caseDocumentRepository;
    private final CaseStatusService caseStatusService;
    private final ClaimsAnalysisClient claimsAnalysisClient;

    @Override
    public CaseResponse createCase(CaseRequest request, Map<String, MultipartFile> documents) {
        Case entity = Case.builder()
                .branch(request.branch())
                .product(request.product())
                .claimCause(request.claimCause())
                .insuredItem(request.insuredItem())
                .insuredId(request.insuredId())
                .policyNumber(request.policyNumber())
                .description(request.description())
                .eventDate(request.eventDate())
                .eventLocation(request.eventLocation())
                .claimedAmount(request.claimedAmount())
                .status(CaseStatus.PENDING_CLASSIFICATION)
                .build();

        Case saved = caseRepository.save(entity);
        caseStatusService.recordCreation(saved, StatusChangeActor.INSURED, "denuncia registrada");
        storeDocuments(saved.getId(), documents);
        claimsAnalysisClient.analyzeAndPersist(saved, caseDocumentRepository.findByCaseId(saved.getId()));

        return toResponse(saved);
    }

    @Override
    public CaseResponse addDocumentsAndReclassify(Long caseId, Map<String, MultipartFile> documents) {
        Case entity = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));

        storeDocuments(caseId, documents);

        entity.setAnalysisClassification(null);
        entity.setAnalysisConfidence(null);
        entity.setAnalysisDetail(null);
        caseStatusService.transition(entity, CaseStatus.PENDING_CLASSIFICATION,
                StatusChangeActor.INSURED, "documentación adicional subida");

        claimsAnalysisClient.analyzeAndPersist(entity, caseDocumentRepository.findByCaseId(caseId));
        return toResponse(entity);
    }

    /** Persists each uploaded document, replacing any prior document of the same type. */
    private void storeDocuments(Long caseId, Map<String, MultipartFile> documents) {
        if (documents == null) {
            return;
        }
        documents.forEach((type, file) -> {
            byte[] content;
            try {
                content = file.getBytes();
            } catch (IOException e) {
                throw new DocumentReadException(type, e);
            }
            CaseDocument document = caseDocumentRepository.findByCaseIdAndType(caseId, type)
                    .orElseGet(() -> CaseDocument.builder().caseId(caseId).type(type).build());
            document.setFilename(file.getOriginalFilename());
            document.setContentType(file.getContentType());
            document.setContent(content);
            caseDocumentRepository.save(document);
        });
    }

    @Override
    public CaseResponse getCase(Long caseId) {
        Case entity = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));
        return toResponse(entity);
    }

    @Override
    public List<CaseResponse> listCases(CaseStatus status) {
        List<Case> entities = status != null
                ? caseRepository.findByStatusOrderByIdDesc(status)
                : caseRepository.findAllByOrderByIdDesc();
        return entities.stream().map(this::toResponse).toList();
    }

    @Override
    public void recordAnalystDecision(Long caseId, AnalystDecisionRequest request) {
        Case entity = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));

        claimsAnalysisClient.forwardAnalystDecision(caseId, request);

        CaseStatus targetStatus = "REJECT".equalsIgnoreCase(request.decision())
                || "RECHAZAR".equalsIgnoreCase(request.decision())
                ? CaseStatus.REJECTED
                : CaseStatus.APPROVED;

        caseStatusService.transition(entity, targetStatus,
                StatusChangeActor.ANALYST, "decisión del analista: " + request.decision());
    }

    private CaseResponse toResponse(Case entity) {
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
                entity.getClaimedAmount(),
                entity.getAnalysisClassification(),
                entity.getAnalysisConfidence() != null ? entity.getAnalysisConfidence() : 0.0,
                entity.getAnalysisDetail()
        );
    }
}
