package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.rules.dto.CoverageLimitsDto;
import ar.edu.utn.frba.arbiter.rules.dto.EvaluableRulesDto;
import ar.edu.utn.frba.arbiter.rules.services.InternalCoverageLimitsService;
import ar.edu.utn.frba.arbiter.rules.services.InternalEvaluableRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reglas duras evaluables por cobertura, servidas al motor de clasificación (no a un referente).
 * Hoy: exclusiones de cobertura (qué hechos generadores NO cubre). El CRUD del referente es un
 * incremento aparte (plan-reglas-evaluables.md, opción (a)): por ahora las reglas entran por seed.
 */
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
@Tag(name = "Reglas evaluables", description = "Reglas duras evaluables por código (exclusiones de cobertura)")
public class EvaluableRuleController {

    private final InternalEvaluableRuleService internalEvaluableRules;
    private final InternalCoverageLimitsService internalCoverageLimits;

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
}
