package ar.edu.utn.frba.arbiter.cases.services;

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
        // Clear the cached risk too so the recalculation window reads as "sin scorear"/recalculando,
        // never a stale band. It's re-populated by the classification poll once the new score lands.
        entity.setRiskScore(null);
        entity.setRiskBand(null);
        entity.setRiskBreakdown(null);
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
                entity.getAnalysisDetail(),
                entity.getRiskScore(),
                entity.getRiskBand(),
                entity.getRiskBreakdown()
        );
    }
}
