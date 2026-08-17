package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.rules.dto.CatalogOption;
import ar.edu.utn.frba.arbiter.rules.dto.CoverageInclusionConfig;
import ar.edu.utn.frba.arbiter.rules.dto.CoverageInclusionResponse;
import ar.edu.utn.frba.arbiter.rules.services.CoverageInclusionRuleService;
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
 * Backoffice del referente: hechos generadores QUE SÍ cubre cada cobertura y el catálogo de hechos
 * generadores del ramo para poblar el selector. A diferencia de las exclusiones en texto (ver
 * {@link RuleTextController}), esta la evalúa el motor por código y la audita en {@code rule_result}.
 * El schema del tenant sale del JWT: el referente solo toca su aseguradora.
 */
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
@Tag(name = "Inclusiones de cobertura", description = "Hechos generadores cubiertos por cada cobertura (regla dura evaluable)")
public class CoverageInclusionController {

    private final CoverageInclusionRuleService service;

    @GetMapping("/claim-causes")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Hechos generadores de un ramo",
            description = "id + nombre de los hechos generadores del ramo, para el selector de inclusiones.")
    public List<CatalogOption> claimCauses(@RequestParam Long branchId) {
        return service.listClaimCauses(branchId);
    }

    @GetMapping("/coverage-inclusions")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Hechos generadores cubiertos por una cobertura",
            description = "Ids de los hechos generadores que la cobertura SÍ cubre. Sin regla ⇒ lista vacía "
                    + "(no cubre nada — el referente tiene que configurarlo).")
    public CoverageInclusionConfig get(@RequestParam Long coverageId) {
        return service.get(coverageId);
    }

    @GetMapping("/coverage-inclusions/covered-claim-causes")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Nombres de los hechos generadores cubiertos por una cobertura",
            description = "Nombres (no ids) de los hechos generadores que la cobertura SÍ cubre, para el "
                    + "wizard de alta de denuncia: marca en rojo el hecho elegido que la póliza no cubre. "
                    + "A diferencia del endpoint del referente, es de solo lectura y accesible a cualquier "
                    + "usuario autenticado (el tenant del JWT recorta a su aseguradora). Whitelist vacía ⇒ "
                    + "lista vacía (la cobertura no cubre nada).")
    public List<String> coveredClaimCauses(@RequestParam Long coverageId) {
        return service.listCoveredClaimCauseNames(coverageId);
    }

    @PutMapping("/coverage-inclusions")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Guardar hechos generadores cubiertos por una cobertura",
            description = "Crea o actualiza la regla COVERAGE_INCLUSION de (ramo, cobertura). Cada cambio "
                    + "deja snapshot en el historial. Devuelve la fila de insurer_rule tal como quedó, con su id.")
    public CoverageInclusionResponse upsert(
            @RequestParam Long branchId,
            @RequestParam Long coverageId,
            @RequestBody CoverageInclusionConfig config,
            Authentication authentication) {
        return service.upsert(branchId, coverageId, config, authentication.getName());
    }
}
