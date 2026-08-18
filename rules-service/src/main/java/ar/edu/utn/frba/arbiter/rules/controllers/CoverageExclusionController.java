package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.rules.dto.CatalogOption;
import ar.edu.utn.frba.arbiter.rules.dto.CoverageExclusionConfig;
import ar.edu.utn.frba.arbiter.rules.dto.CoverageExclusionResponse;
import ar.edu.utn.frba.arbiter.rules.services.CoverageExclusionRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Referente backoffice: hard coverage exclusions (which claim causes each coverage does NOT cover)
 * and the branch's claim cause catalog to populate the picker. Unlike the text exclusions
 * ({@link RuleTextController}), these are evaluated by the engine in code and audited in
 * {@code rule_result}. The tenant schema comes from the JWT: the referente only touches their own
 * insurer.
 */
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
@Tag(name = "Exclusiones de cobertura", description = "Hechos generadores excluidos por cobertura (regla dura evaluable)")
public class CoverageExclusionController {

    private final CoverageExclusionRuleService service;

    @GetMapping("/claim-causes")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Hechos generadores de un ramo",
            description = "id + nombre de los hechos generadores del ramo, para el selector de exclusiones.")
    public List<CatalogOption> claimCauses(@RequestParam Long branchId) {
        return service.listClaimCauses(branchId);
    }

    @GetMapping("/coverage-exclusions")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Exclusiones duras de una cobertura",
            description = "Ids de los hechos generadores que la cobertura NO cubre. Sin regla ⇒ lista vacía.")
    public CoverageExclusionConfig get(@RequestParam Long coverageId) {
        return service.get(coverageId);
    }

    @PutMapping("/coverage-exclusions")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Guardar exclusiones duras de una cobertura",
            description = "Crea o actualiza la regla COVERAGE_EXCLUSION de (ramo, cobertura). Cada cambio "
                    + "deja snapshot en el historial. Devuelve la fila de insurer_rule tal como quedó, con su id.")
    public CoverageExclusionResponse upsert(
            @RequestParam Long branchId,
            @RequestParam Long coverageId,
            @RequestBody CoverageExclusionConfig config,
            Authentication authentication) {
        return service.upsert(branchId, coverageId, config, authentication.getName());
    }
}
