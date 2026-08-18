package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.rules.dto.CoverageLimitsDto;
import ar.edu.utn.frba.arbiter.rules.dto.EvaluableRulesDto;
import ar.edu.utn.frba.arbiter.rules.dto.ExpertDerivationDto;
import ar.edu.utn.frba.arbiter.rules.dto.ScoringConfigDto;
import ar.edu.utn.frba.arbiter.rules.services.InternalCoverageLimitsService;
import ar.edu.utn.frba.arbiter.rules.services.InternalEvaluableRuleService;
import ar.edu.utn.frba.arbiter.rules.services.InternalExpertDerivationService;
import ar.edu.utn.frba.arbiter.rules.services.ScoringConfigurationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hard evaluable rules per coverage, served to the classification engine (not to a referente).
 * Today: coverage exclusions (which claim causes it does NOT cover). The referente's CRUD is a
 * separate increment (plan-reglas-evaluables.md, option (a)): for now the rules come in via seed.
 */
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
@Tag(name = "Reglas evaluables", description = "Reglas duras evaluables por código (exclusiones de cobertura)")
public class EvaluableRuleController {

    private final InternalEvaluableRuleService internalEvaluableRules;
    private final InternalCoverageLimitsService internalCoverageLimits;
    private final ScoringConfigurationService scoringConfigurationService;
    private final InternalExpertDerivationService internalExpertDerivation;

    @GetMapping("/internal/evaluable")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "[interno] Reglas evaluables por cobertura",
            description = "Lectura system-to-system para classification-service (sin rol REFERENTE): la "
                    + "usa el motor con un token de servicio que lleva el tenant. Keyea por cobertura —lo "
                    + "que el claim tiene a mano—. Sin configuración devuelve una lista vacía, nunca 404: "
                    + "el motor compone esto sobre su baseline.")
    public EvaluableRulesDto internalEvaluable(@RequestParam Long coverageId) {
        return internalEvaluableRules.getByCoverage(coverageId);
    }

    @GetMapping("/internal/coverage-limits")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "[interno] Límites de la cobertura (plazo de denuncia, tope de eventos)",
            description = "Lectura system-to-system para el motor: columnas report_deadline_hours y "
                    + "max_events_per_year de la cobertura, que el motor evalúa por código (D10/D11). "
                    + "Sin cobertura ⇒ vacío.")
    public CoverageLimitsDto internalCoverageLimits(@RequestParam Long coverageId) {
        return internalCoverageLimits.getByCoverage(coverageId);
    }

    @GetMapping("/internal/scoring")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "[interno] Scoring de fraude de la aseguradora",
            description = "Lectura system-to-system para el motor: factores + bandas configurados por el "
                    + "referente (una config por aseguradora). Sin config ⇒ enabled=false; el motor cae a su "
                    + "baseline. Es lo que hace que el panel de scoring del referente afecte la clasificación.")
    public ScoringConfigDto internalScoring() {
        return scoringConfigurationService.get();
    }

    @GetMapping("/internal/expert-derivation")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "[interno] Política de derivación a peritaje del ramo",
            description = "Lectura system-to-system para cases-service: si esta aseguradora deriva "
                    + "siniestros de este ramo a un perito externo, y desde qué monto reclamado. Sin regla "
                    + "configurada ⇒ enabled=false, y el analista no ve la opción de derivar: el peritaje "
                    + "es opt-in, porque abajo de cierto monto cuesta más que el siniestro. No decide nada "
                    + "—habilita—: quién y cuándo deriva sigue siendo el analista.")
    public ExpertDerivationDto internalExpertDerivation(@RequestParam Long branchId) {
        return internalExpertDerivation.getByBranch(branchId);
    }
}
