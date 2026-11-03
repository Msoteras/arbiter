package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.rules.dto.ExpertDerivationRuleDto;
import ar.edu.utn.frba.arbiter.rules.dto.RepairDerivationRuleDto;
import ar.edu.utn.frba.arbiter.rules.services.ExpertDerivationService;
import ar.edu.utn.frba.arbiter.rules.services.RepairDerivationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A branch's derivation rules as the referente configures them. The system-to-system reads
 * cases-service uses live in {@link EvaluableRuleController}.
 */
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
@Tag(name = "Derivaciones",
        description = "Cuándo un ramo deriva a peritaje y a reparación")
public class DerivationRuleController {

    private final ExpertDerivationService expertDerivation;
    private final RepairDerivationService repairDerivation;

    @GetMapping("/expert-derivation")
    @PreAuthorize("hasAnyRole('REFERENTE_ASEGURADORA', 'ANALISTA_SINIESTROS')")
    @Operation(summary = "Derivación a peritaje del ramo",
            description = "Si el ramo deriva a un perito externo y desde qué monto reclamado. El "
                    + "monto viaja aunque la regla esté apagada. Sin regla ⇒ enabled=false y sin monto.")
    public ExpertDerivationRuleDto getExpertDerivation(@RequestParam Long branchId) {
        return expertDerivation.getRule(branchId);
    }

    @PutMapping("/expert-derivation")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Guardar la derivación a peritaje del ramo",
            description = "Crea o actualiza la fila de insurer_rule EXPERT_DERIVATION del ramo. "
                    + "Encendida exige monto mínimo. Cada cambio deja snapshot en el historial.")
    public ExpertDerivationRuleDto upsertExpertDerivation(
            @RequestParam Long branchId,
            @RequestBody @Valid ExpertDerivationRuleDto rule,
            Authentication authentication) {
        return expertDerivation.upsert(branchId, rule, authentication.getName());
    }

    @GetMapping("/repair-derivation")
    @PreAuthorize("hasAnyRole('REFERENTE_ASEGURADORA', 'ANALISTA_SINIESTROS')")
    @Operation(summary = "Derivación a reparación del ramo",
            description = "Si el ramo deriva a un servicio técnico y para qué hechos generadores. "
                    + "Los hechos viajan aunque la regla esté apagada. Sin regla ⇒ enabled=false y "
                    + "lista vacía.")
    public RepairDerivationRuleDto getRepairDerivation(@RequestParam Long branchId) {
        return repairDerivation.getRule(branchId);
    }

    @PutMapping("/repair-derivation")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Guardar la derivación a reparación del ramo",
            description = "Crea o actualiza la fila de insurer_rule REPAIR_DERIVATION del ramo. "
                    + "Encendida exige al menos un hecho generador, y todos tienen que ser del ramo. "
                    + "Cada cambio deja snapshot en el historial.")
    public RepairDerivationRuleDto upsertRepairDerivation(
            @RequestParam Long branchId,
            @RequestBody @Valid RepairDerivationRuleDto rule,
            Authentication authentication) {
        return repairDerivation.upsert(branchId, rule, authentication.getName());
    }
}
