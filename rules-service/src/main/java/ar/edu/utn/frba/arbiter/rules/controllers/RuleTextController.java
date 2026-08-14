package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.rules.dto.RuleTextResponse;
import ar.edu.utn.frba.arbiter.rules.dto.RuleTextsDto;
import ar.edu.utn.frba.arbiter.rules.services.InternalRuleTextService;
import ar.edu.utn.frba.arbiter.rules.services.RuleTextService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * Common exclusions and business rules as free text, by branch (no table of their own in the DER —
 * see {@link RuleTextService}).
 */
@RestController
@RequestMapping("/api/v1/rules")
@Tag(name = "Reglas de negocio (texto)", description = "Exclusiones comunes y reglas de negocio en texto libre, por ramo")
public class RuleTextController {

    private final RuleTextService commonExclusions;
    private final RuleTextService businessRules;
    private final InternalRuleTextService internalRuleTexts;

    public RuleTextController(
            @Qualifier("commonExclusionsRuleTextService") RuleTextService commonExclusions,
            @Qualifier("businessRulesRuleTextService") RuleTextService businessRules,
            InternalRuleTextService internalRuleTexts) {
        this.commonExclusions = commonExclusions;
        this.businessRules = businessRules;
        this.internalRuleTexts = internalRuleTexts;
    }

    @GetMapping("/internal/rule-texts")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "[interno] Exclusiones y reglas de negocio por cobertura",
            description = "Lectura system-to-system para classification-service (sin rol REFERENTE): la usa "
                    + "el motor con un token de servicio que lleva el tenant. Keyea por cobertura —lo que el "
                    + "claim tiene a mano— y resuelve el ramo puertas adentro. Sin configuración devuelve "
                    + "listas vacías, nunca 404: el motor compone esto sobre su baseline.")
    public RuleTextsDto internalRuleTexts(@RequestParam Long coverageId) {
        return internalRuleTexts.getByCoverage(coverageId);
    }

    @GetMapping("/exclusions")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Exclusiones comunes de un ramo")
    public List<String> getExclusions(@RequestParam Long branchId) {
        return commonExclusions.get(branchId);
    }

    @PutMapping("/exclusions")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Guardar exclusiones comunes de un ramo",
            description = "Devuelve la fila de insurer_rule tal como quedó persistida, con su id.")
    public RuleTextResponse saveExclusions(@RequestParam Long branchId, @RequestBody List<String> items, Authentication authentication) {
        return commonExclusions.upsert(branchId, items, authentication.getName());
    }

    @GetMapping("/business-rules")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Reglas de negocio (texto libre) de un ramo")
    public List<String> getBusinessRules(@RequestParam Long branchId) {
        return businessRules.get(branchId);
    }

    @PutMapping("/business-rules")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Guardar reglas de negocio (texto libre) de un ramo",
            description = "Devuelve la fila de insurer_rule tal como quedó persistida, con su id.")
    public RuleTextResponse saveBusinessRules(@RequestParam Long branchId, @RequestBody List<String> items, Authentication authentication) {
        return businessRules.upsert(branchId, items, authentication.getName());
    }
}
