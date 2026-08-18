package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.rules.dto.HardRuleDto;
import ar.edu.utn.frba.arbiter.rules.services.HardRuleService;
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
 * Referente-facing backoffice: which hard temporal rules the insurer has active for a coverage
 * (waiting period, report deadline, police-report deadline, events-per-year cap). This is what
 * lets these rules be adjusted without a redeploy — until now the police-report deadline was a
 * fixed 72h property for every company, against decision #12.
 *
 * <p>Coverage window and arrears aren't here: they're scoped to the whole insurer, not one
 * coverage — see {@link ar.edu.utn.frba.arbiter.rules.services.InsurerHardRuleService}.
 *
 * <p>The waiting period, report deadline and events cap thresholds <b>aren't edited here</b>:
 * they're terms of the contract and live on the coverage (Coverages tab). Here a rule gets turned
 * on and off, and the one threshold with no column of its own — the police-report deadline — gets
 * set.
 */
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
@Tag(name = "Reglas duras", description = "Activación y umbrales de las reglas duras temporales por cobertura")
public class HardRuleController {

    private final HardRuleService service;

    @GetMapping("/hard-rules")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Reglas duras temporales de una cobertura",
            description = "Siempre devuelve el catálogo completo: la regla que la aseguradora nunca "
                    + "configuró viene apagada (enabled=false), que es como se comporta hoy — el motor no "
                    + "la evalúa.")
    public List<HardRuleDto> get(@RequestParam Long branchId, @RequestParam Long coverageId) {
        return service.get(branchId, coverageId);
    }

    @PutMapping("/hard-rules")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Guardar las reglas duras temporales de una cobertura",
            description = "Crea o actualiza una fila de insurer_rule por regla. Cada cambio deja snapshot "
                    + "en el historial. El cambio impacta en la próxima clasificación sin reiniciar nada: "
                    + "el motor lee estas filas en cada corrida.")
    public List<HardRuleDto> upsert(
            @RequestParam Long branchId,
            @RequestParam Long coverageId,
            @RequestBody List<HardRuleDto> rules,
            Authentication authentication) {
        return service.upsert(branchId, coverageId, rules, authentication.getName());
    }
}
