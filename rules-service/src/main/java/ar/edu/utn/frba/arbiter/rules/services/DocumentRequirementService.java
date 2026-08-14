package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.rules.dto.DocumentRequirementDto;
import ar.edu.utn.frba.arbiter.rules.exceptions.BranchNotFoundException;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.DocumentRequirement;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.DocumentRequirementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * A branch's AgendaDocumental, for the referente's Documentación tab. The DER
 * (document_requirement / "requisito_documental") keys by branch + claim cause; the screen edits it
 * as a flat list per branch (docs/decisiones-reglas-a-validar.md, D5). The gap is bridged with the
 * same pattern Fast Track uses for coverages: fan-out — the same list is written for every claim
 * cause of the branch. No history: the DER has no "historial_requisito_documental".
 */
@Service
@RequiredArgsConstructor
public class DocumentRequirementService {

    private final DocumentRequirementRepository documentRequirementRepository;
    private final ClaimCauseRepository claimCauseRepository;
    private final BranchRepository branchRepository;

    @Transactional(readOnly = true)
    public List<String> get(Long branchId) {
        return documentRequirementRepository.findByBranch_Id(branchId).stream()
                .map(DocumentRequirement::getDocumentType)
                .distinct()
                .toList();
    }

    /**
     * Same as {@link #get(Long)} but resolving the branch by name — it's what the insured (when
     * uploading) and the analyst (missing-documents checklist) have at hand; only the referente
     * handles the numeric id. Unknown branch ⇒ empty list.
     */
    @Transactional(readOnly = true)
    public List<String> getByBranchName(String branchName) {
        return branchRepository.findByName(branchName)
                .map(branch -> get(branch.getId()))
                .orElse(List.of());
    }

    @Transactional
    public List<DocumentRequirementDto> upsert(Long branchId, List<String> documentTypes) {
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new BranchNotFoundException(branchId));
        List<ClaimCause> claimCauses = claimCauseRepository.findByBranch_IdOrderByNameAsc(branchId);
        if (claimCauses.isEmpty()) {
            throw new InvalidRuleConfigurationException(
                    "El ramo no tiene hechos generadores cargados en el catálogo.");
        }
        List<DocumentRequirementDto> persisted = new ArrayList<>();
        for (ClaimCause claimCause : claimCauses) {
            documentRequirementRepository.deleteByBranch_IdAndClaimCause_Id(branchId, claimCause.getId());
            List<DocumentRequirement> requirements = documentTypes.stream()
                    .map(type -> DocumentRequirement.builder()
                            .documentType(type)
                            .mandatory(true)
                            .branch(branch)
                            .claimCause(claimCause)
                            .build())
                    .toList();
            documentRequirementRepository.saveAll(requirements).forEach(saved -> persisted.add(new DocumentRequirementDto(
                    saved.getId(), saved.getDocumentType(), saved.getClaimCause().getId(), saved.isMandatory())));
        }
        return persisted;
    }
}
