package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.rules.dto.FraudRecordRuleDto;
import ar.edu.utn.frba.arbiter.rules.services.FraudRecordRuleService;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * The insurer's policy on fraud records: whether an earlier confirmed fraud counts against a new
 * claim, for how long, and whether it disqualifies it from Fast Track.
 */
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
@Tag(name = "Antecedente de fraude",
        description = "Ventana de vigencia y veto de Fast Track del antecedente de fraude")
public class FraudRecordRuleController {

    private final FraudRecordRuleService service;

    @GetMapping("/fraud-record-rule")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Política de antecedentes de fraude",
            description = "La aseguradora que nunca la configuró la recibe apagada (enabled=false): "
                    + "los antecedentes se registran y se ven, pero no puntúan ni vetan.")
    public FraudRecordRuleDto get() {
        return service.get();
    }

    @PutMapping("/fraud-record-rule")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Guardar la política de antecedentes de fraude",
            description = "Crea o actualiza la fila de insurer_rule FRAUD_RECORD, sin rama ni cobertura. "
                    + "Cada cambio deja snapshot en el historial.")
    public FraudRecordRuleDto upsert(
            @RequestBody @Valid FraudRecordRuleDto rule, Authentication authentication) {
        return service.upsert(rule, authentication.getName());
    }

    @GetMapping("/internal/fraud-record-rule")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "[interno] Política de antecedentes de fraude",
            description = "Lectura system-to-system para classification-service (sin rol REFERENTE): "
                    + "la usa el motor para saber si el antecedente cuenta y con qué ventana. La regla "
                    + "es de toda la aseguradora, así que no lleva ramo ni cobertura.")
    public FraudRecordRuleDto internalGet() {
        return service.get();
    }
}
