package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseEntity;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Mock implementation for dev profile — synchronous classification with keyword matching.
 * Falls back when classification-service is unavailable or too slow.
 */
@Component
@Profile({"dev", "test", "default"})
@RequiredArgsConstructor
public class MockClaimsAnalysisClient implements ClaimsAnalysisClient {

    private final CaseRepository caseRepository;

    @Override
    public AnalysisResult analyze(CaseRequest request) {
        String classification = classify(request);
        String detail = switch (classification) {
            case "FAST_TRACK" -> "Low-complexity case detected from the initial information.";
            case "LLM_NO_RECOMIENDA_APROBAR" -> "Moderate risk indicators found, requires detailed review.";
            case "FALTA_DOCUMENTACION" -> "Missing key documentation to continue the analysis.";
            default -> "The case needs additional manual review.";
        };
        double confidence = classification.equals("LLM_SOLICITA_REVISION_MANUAL") ? 0.58 : 0.92;
        return new AnalysisResult(classification, confidence, detail);
    }

    private String classify(CaseRequest request) {
        String cause = request.claimCause().toLowerCase();
        String location = request.eventLocation().toLowerCase();
        if (cause.contains("rotura") || location.contains("casa") || location.contains("hogar")) {
            return "FAST_TRACK";
        }
        if (cause.contains("robo") || cause.contains("hurto") || cause.contains("pérdida") || cause.contains("perdí")) {
            return "LLM_NO_RECOMIENDA_APROBAR";
        }
        if (request.description().length() < 30) {
            return "FALTA_DOCUMENTACION";
        }
        return "LLM_SOLICITA_REVISION_MANUAL";
    }

    @Override
    public AnalysisResult analyzeAndPersist(CaseEntity caseEntity) {
        // Mock: classify synchronously
        AnalysisResult result = analyze(new CaseRequest(
            caseEntity.getBranch(),
            caseEntity.getProduct(),
            caseEntity.getClaimCause(),
            caseEntity.getInsuredItem(),
            caseEntity.getInsuredId(),
            caseEntity.getPolicyNumber(),
            caseEntity.getDescription(),
            caseEntity.getEventDate(),
            caseEntity.getEventLocation()
        ));
        
        // Update entity with classification result
        caseEntity.setAnalysisClassification(result.classification());
        caseEntity.setAnalysisConfidence(result.confidence());
        caseEntity.setAnalysisDetail(result.detail());
        caseEntity.setStatus("CLASSIFIED");
        
        // Persist immediately (mock is synchronous)
        caseRepository.save(caseEntity);
        return result;
    }

    @Override
    public boolean refreshClassification(CaseEntity caseEntity) {
        // Mock: already classified (was done in analyzeAndPersist)
        return false;
    }
}
