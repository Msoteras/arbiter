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
 * Agenda documental (AgendaDocumental) de un ramo, para la solapa Documentación del referente. El
 * DER (document_requirement / "requisito_documental") keyea por rama + hecho generador; la pantalla
 * la edita como una lista plana por ramo (docs/decisiones-reglas-a-validar.md, D5). Resolvemos la
 * diferencia con el mismo patrón que Fast Track usa con las coberturas: fan-out — la misma lista se
 * escribe para todos los hechos generadores del ramo. Sin historial: el DER no tiene
 * "historial_requisito_documental".
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
     * Igual que {@link #get(Long)} pero resolviendo el ramo por nombre — es lo que el asegurado (al
     * subir) y el analista (checklist de faltantes) tienen a mano; el id numérico solo lo maneja el
     * referente. Ramo desconocido ⇒ lista vacía.
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
