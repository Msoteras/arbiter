package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.rules.dto.InsurerHardRuleDto;
import ar.edu.utn.frba.arbiter.rules.services.InsurerHardRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

/**
 * Referente-facing backoffice for the two hard rules scoped to the whole insurer instead of one
 * coverage: coverage window ({@code POLICY_IN_FORCE}) and arrears ({@code POLICY_STANDING}). See
 * {@link InsurerHardRuleService}'s javadoc for why these two aren't in {@link HardRuleController}.
 */
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
@Tag(name = "Reglas duras de la aseguradora",
        description = "Activación de las reglas duras temporales de toda la aseguradora (vigencia, mora)")
public class InsurerHardRuleController {

    private final InsurerHardRuleService service;

    @GetMapping("/insurer-hard-rules")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Reglas duras de toda la aseguradora",
            description = "Siempre devuelve las dos: la que la aseguradora nunca configuró viene apagada "
                    + "(enabled=false) y, si es la de mora, con onArrears=STANDBY (el comportamiento previo "
                    + "a que esta regla existiera).")
    public List<InsurerHardRuleDto> get() {
        return service.get();
    }

    @PutMapping("/insurer-hard-rules")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Guardar las reglas duras de toda la aseguradora",
            description = "Crea o actualiza una fila de insurer_rule por regla, sin rama ni cobertura. "
                    + "Cada cambio deja snapshot en el historial.")
    public List<InsurerHardRuleDto> upsert(
            @RequestBody List<InsurerHardRuleDto> rules, Authentication authentication) {
        return service.upsert(rules, authentication.getName());
    }

    @GetMapping("/internal/policy-standing")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "[interno] Regla de mora de la póliza",
            description = "Lectura system-to-system para cases-service (sin rol REFERENTE): la usa el "
                    + "gate de alta con un token de servicio que lleva el tenant, antes de crear el "
                    + "expediente. Sin configuración vuelve enabled=false — el gate no rechaza nada.")
    public InsurerHardRuleDto internalPolicyStanding() {
        return service.getPolicyStanding();
    }
}
