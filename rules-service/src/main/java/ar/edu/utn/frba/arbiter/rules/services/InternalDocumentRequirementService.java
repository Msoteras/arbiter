package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import ar.edu.utn.frba.arbiter.rules.models.repositories.CoverageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Lectura system-to-system de la agenda documental para el motor de clasificación.
 *
 * <p>Misma asimetría de claves que {@link InternalRuleTextService}: el referente configura la agenda
 * <b>por ramo</b>, pero el claim que llega al motor solo trae un {@code coverageId} (el ramo y el
 * hecho generador le llegan como nombres, ver {@code ClaimReport}). Así que acá se resuelve
 * cobertura → ramo y recién después se leen los tipos de documento requeridos. Es lo que el gate de
 * faltantes ({@code ClassificationOrchestrator.checkRequiredDocuments}) usa para decidir si al
 * expediente le falta documentación — antes salía del mock, ahora de lo que el referente configuró.
 */
@Service
@RequiredArgsConstructor
public class InternalDocumentRequirementService {

    private final CoverageRepository coverageRepository;
    private final DocumentRequirementService documentRequirements;

    /**
     * Devuelve vacío —no 404— cuando la cobertura no existe o el ramo no tiene agenda: el motor
     * compone esto sobre su baseline y una clasificación no se puede caer porque falte configuración.
     */
    @Transactional(readOnly = true)
    public List<String> getByCoverage(Long coverageId) {
        Long branchId = coverageRepository.findById(coverageId)
                .map(Coverage::getBranchId)
                .orElse(null);
        if (branchId == null) {
            return List.of();
        }
        return documentRequirements.get(branchId);
    }
}
