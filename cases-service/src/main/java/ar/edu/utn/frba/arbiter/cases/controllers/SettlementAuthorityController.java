package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.dto.SettlementAuthorityResponse;
import ar.edu.utn.frba.arbiter.cases.dto.SettlementAuthorityUpsertRequest;
import ar.edu.utn.frba.arbiter.cases.services.SettlementAuthorityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Hasta cuánto autoriza un analista por su cuenta en cada ramo (Anexo II del procedimiento).
 * Configuración de la aseguradora, así que es del referente: el analista no puede correr el techo
 * que lo limita a él.
 */
@RestController
@RequestMapping("/api/v1/settlement-authorities")
@RequiredArgsConstructor
@Tag(name = "Settlement authorities", description = "Per-branch limits an analyst can authorize alone")
public class SettlementAuthorityController {

    private final SettlementAuthorityService authorityService;

    @GetMapping
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Attribution ceiling per branch",
            description = "Every branch, including the ones with no ceiling — those come back with a null "
                    + "maxAmount, which means the analyst authorizes any amount in that branch.")
    public List<SettlementAuthorityResponse> list() {
        return authorityService.list();
    }

    @PutMapping("/{branchId}")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Set or clear a branch's ceiling",
            description = "A null maxAmount removes the ceiling: the branch goes back to the analyst "
                    + "authorizing everything.")
    public void set(@PathVariable Long branchId, @RequestBody @Valid SettlementAuthorityUpsertRequest request) {
        authorityService.set(branchId, request);
    }
}
