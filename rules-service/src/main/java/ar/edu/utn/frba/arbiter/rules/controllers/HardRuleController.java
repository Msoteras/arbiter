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
 * Which hard temporal rules the insurer has active for a coverage (waiting period, report deadline,
 * police-report deadline, events-per-year cap). Coverage window and arrears are insurer-wide: see
 * {@link ar.edu.utn.frba.arbiter.rules.services.InsurerHardRuleService}.
 *
 * <p>Only the on/off switch and the police-report deadline are edited here; the other thresholds
 * are contract terms stored on the coverage.
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
