package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.rules.dto.RuleTextsDto;
import ar.edu.utn.frba.arbiter.rules.models.repositories.CoverageRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lectura system-to-system de las reglas en texto para el motor de clasificación.
 *
 * <p>Existe por una asimetría de claves: el referente configura los textos <b>por ramo</b>, pero el
 * claim que llega al motor solo trae un {@code coverageId} — el ramo y el hecho generador le llegan
 * como nombres, no como ids (ver {@code ClaimReport}). Así que acá se resuelve cobertura → ramo y
 * recién después se leen los textos. Es el mismo motivo por el que {@code /internal/fast-track}
 * keyea por cobertura.
 */
@Service
public class InternalRuleTextService {

    private final CoverageRepository coverageRepository;
    private final RuleTextService commonExclusions;
    private final RuleTextService businessRules;

    public InternalRuleTextService(
            CoverageRepository coverageRepository,
            @Qualifier("commonExclusionsRuleTextService") RuleTextService commonExclusions,
            @Qualifier("businessRulesRuleTextService") RuleTextService businessRules) {
        this.coverageRepository = coverageRepository;
        this.commonExclusions = commonExclusions;
        this.businessRules = businessRules;
    }

    /**
     * Devuelve vacío —no 404— cuando la cobertura no existe o no tiene ramo: el motor compone esto
     * sobre su baseline y una clasificación no se puede caer porque falte configuración.
     */
    @Transactional(readOnly = true)
    public RuleTextsDto getByCoverage(Long coverageId) {
        Long branchId = coverageRepository.findById(coverageId)
                .map(coverage -> coverage.getBranchId())
                .orElse(null);
        if (branchId == null) {
            return RuleTextsDto.empty();
        }
        return new RuleTextsDto(commonExclusions.get(branchId), businessRules.get(branchId));
    }
}
