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

/**
 * Agenda documental (AgendaDocumental) de un ramo, para la solapa Documentación del referente. El
 * DER (document_requirement / "requisito_documental") keyea por rama + hecho generador, y desde
 * D5 (docs/decisiones-reglas-a-validar.md) la pantalla también edita por hecho generador — ya no
 * hace fan-out de una lista plana a todos los hechos generadores del ramo. Sin historial: el DER no
 * tiene "historial_requisito_documental".
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
     * Igual que {@link #get(Long, Long)} pero resolviendo el hecho generador por nombre dentro de un
     * ramo ya conocido por id — lo que el motor tiene a mano (ver {@code ClaimReport.claimCause()}).
     * Hecho generador desconocido en ese ramo ⇒ lista vacía.
     */
    @Transactional(readOnly = true)
    public List<String> getByBranchIdAndClaimCauseName(Long branchId, String claimCauseName) {
        return claimCauseRepository.findByBranch_IdAndName(branchId, claimCauseName)
                .map(claimCause -> get(branchId, claimCause.getId()))
                .orElse(List.of());
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
