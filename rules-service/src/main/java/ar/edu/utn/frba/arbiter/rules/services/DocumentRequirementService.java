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

import java.util.List;
import java.util.Optional;

/**
 * A branch's AgendaDocumental, for the referente's Documentación tab. The DER
 * (document_requirement / "requisito_documental") keys by branch + claim cause, and since D5
 * (docs/decisiones-reglas-a-validar.md) the screen edits it per claim cause too — no longer fanning
 * a flat list out to every claim cause of the branch. No history: the DER has no
 * "historial_requisito_documental".
 */
@Service
@RequiredArgsConstructor
public class DocumentRequirementService {

    private final DocumentRequirementRepository documentRequirementRepository;
    private final ClaimCauseRepository claimCauseRepository;
    private final BranchRepository branchRepository;

    @Transactional(readOnly = true)
    public List<String> get(Long branchId, Long claimCauseId) {
        return documentRequirementRepository.findByBranch_IdAndClaimCause_Id(branchId, claimCauseId).stream()
                .map(DocumentRequirement::getDocumentType)
                .distinct()
                .toList();
    }

    /**
     * Same as {@link #get(Long, Long)} but resolving the claim cause by name within a branch already
     * known by id — what the engine has at hand (see {@code ClaimReport.claimCause()}). Unknown claim
     * cause in that branch ⇒ empty list.
     */
    @Transactional(readOnly = true)
    public List<String> getByBranchIdAndClaimCauseName(Long branchId, String claimCauseName) {
        return findByBranchIdAndClaimCauseName(branchId, claimCauseName).orElse(List.of());
    }

    /**
     * Same read, but telling apart "this claim cause requires no documents" (an empty list inside
     * the Optional) from "there is no such claim cause in this branch" (empty Optional). The engine
     * needs the distinction to know whether it may fall back to its baseline; the callers that just
     * render a checklist don't, and keep using the plain method above.
     */
    @Transactional(readOnly = true)
    public Optional<List<String>> findByBranchIdAndClaimCauseName(Long branchId, String claimCauseName) {
        return claimCauseRepository.findByBranch_IdAndName(branchId, claimCauseName)
                .map(claimCause -> get(branchId, claimCause.getId()));
    }

    /**
     * Igual que {@link #getByBranchIdAndClaimCauseName(Long, String)} pero resolviendo también el
     * ramo por nombre — es lo que el asegurado (al subir) y el analista (checklist de faltantes)
     * tienen a mano; los ids numéricos solo los maneja el referente. Ramo desconocido ⇒ lista vacía.
     */
    @Transactional(readOnly = true)
    public List<String> getByBranchAndClaimCauseNames(String branchName, String claimCauseName) {
        return branchRepository.findByName(branchName)
                .map(branch -> getByBranchIdAndClaimCauseName(branch.getId(), claimCauseName))
                .orElse(List.of());
    }

    @Transactional
    public List<DocumentRequirementDto> upsert(Long branchId, Long claimCauseId, List<String> documentTypes) {
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new BranchNotFoundException(branchId));
        ClaimCause claimCause = claimCauseRepository.findById(claimCauseId)
                .filter(cc -> cc.getBranch().getId().equals(branchId))
                .orElseThrow(() -> new InvalidRuleConfigurationException(
                        "El hecho generador " + claimCauseId + " no pertenece al ramo " + branchId + "."));

        documentRequirementRepository.deleteByBranch_IdAndClaimCause_Id(branchId, claimCauseId);
        List<DocumentRequirement> requirements = documentTypes.stream()
                .map(type -> DocumentRequirement.builder()
                        .documentType(type)
                        .mandatory(true)
                        .branch(branch)
                        .claimCause(claimCause)
                        .build())
                .toList();
        return documentRequirementRepository.saveAll(requirements).stream()
                .map(saved -> new DocumentRequirementDto(
                        saved.getId(), saved.getDocumentType(), saved.getClaimCause().getId(), saved.isMandatory()))
                .toList();
    }
}
