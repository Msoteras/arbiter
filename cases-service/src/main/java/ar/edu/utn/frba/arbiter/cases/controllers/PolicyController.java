package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.services.PolicyCoverageService;
import ar.edu.utn.frba.arbiter.cases.services.PolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Pólizas del asegurado, para autocompletar el alta de denuncia. Puerta del asegurado
 * a la BD Aseguradora (vía {@link PolicyService} → InsurerAdapter). Multi-aseguradora:
 * el listado por asegurado agrega las pólizas de todas las compañías.
 */
@RestController
@RequestMapping("/api/v1/policies")
@RequiredArgsConstructor
@Tag(name = "Policies", description = "Insured's policies for claim intake autocomplete")
public class PolicyController {

    private final PolicyService policyService;
    private final PolicyCoverageService policyCoverageService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ASEGURADO', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "List the insured's policies",
            description = """
                    Returns every policy of the given insured across the insurers they belong to
                    (centralized view). Backs the claim wizard: the logged-in insured picks one of
                    their policies instead of typing the number blindly. An ASEGURADO can only ask
                    for their own DNI — 403 otherwise.
                    """)
    public ResponseEntity<List<PolicyResponse>> listByInsured(@RequestParam String insuredId) {
        return ResponseEntity.ok(policyService.listByInsured(insuredId));
    }

    @GetMapping("/{policyNumber}")
    @PreAuthorize("hasAnyRole('ASEGURADO', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Get a policy by number",
            description = """
                    Returns the policy for autocomplete and validity check (upToDate). 404 if not
                    found — and also if it belongs to someone else, so the response never confirms
                    that a policy number exists.
                    """)
    public ResponseEntity<PolicyResponse> getByNumber(@PathVariable String policyNumber) {
        return ResponseEntity.ok(policyService.getByNumber(policyNumber));
    }

    @GetMapping("/{policyNumber}/covered-claim-causes")
    @PreAuthorize("hasRole('ASEGURADO')")
    @Operation(summary = "Hechos generadores que la póliza cubre",
            description = """
                    Nombres de los hechos generadores que la cobertura de esta póliza SÍ cubre, para el
                    wizard de alta de denuncia: sobre el catálogo del ramo, marca en rojo el hecho elegido
                    que la póliza no cubre. Un ASEGURADO solo puede preguntar por sus propias pólizas —
                    404 sobre una ajena, para no confirmar que el número existe. Whitelist vacía (o
                    cobertura sin regla de inclusión) ⇒ lista vacía: la póliza no cubre nada.
                    """)
    public ResponseEntity<List<String>> coveredClaimCauses(@PathVariable String policyNumber) {
        return ResponseEntity.ok(policyCoverageService.coveredClaimCauses(policyNumber));
    }
}
