package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.cases.services.CaseReferenceResolver;
import ar.edu.utn.frba.arbiter.cases.services.PolicyCoverageResolver;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Claim causes are looked up by branch name, the same key {@code CaseReferenceResolver} validates
 * against, so whatever the selector offers resolves. With {@code policyNumber}, causes excluded by
 * the policy's coverages ({@code COVERAGE_EXCLUSION}) are removed.
 */
@RestController
@RequestMapping("/api/v1/claim-causes")
@RequiredArgsConstructor
@Tag(name = "Claim causes", description = "Hechos generadores por ramo")
public class ClaimCauseController {

    private static final Logger log = LoggerFactory.getLogger(ClaimCauseController.class);

    private final ClaimCauseRepository claimCauseRepository;
    private final CaseReferenceResolver referenceResolver;
    private final PolicyCoverageResolver policyCoverageResolver;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Hechos generadores de un ramo",
            description = "Nombres de los hechos generadores del ramo dado, para el selector del alta de "
                    + "denuncia. Ramo desconocido ⇒ lista vacía. Con policyNumber, recorta los que la "
                    + "cobertura de esa póliza excluye.")
    public List<String> byBranch(
            @RequestParam String branch,
            @RequestParam(required = false) String policyNumber
    ) {
        List<ClaimCause> causes = claimCauseRepository.findByBranch_NameOrderByNameAsc(branch);
        Set<Long> excluded = policyNumber == null ? Set.of() : excludedForPolicy(policyNumber);
        return causes.stream()
                .filter(c -> !excluded.contains(c.getId()))
                .map(ClaimCause::getName)
                .toList();
    }

    /**
     * Best-effort: on failure the wizard shows the unfiltered list, and the coverage rules still catch
     * an excluded claim cause during classification.
     */
    private Set<Long> excludedForPolicy(String policyNumber) {
        try {
            String insuredId = CallerContext.get().insuredId();
            if (insuredId == null) {
                return Set.of();
            }
            Insured insured = referenceResolver.resolveInsured(insuredId);
            Policy policy = referenceResolver.resolvePolicy(policyNumber, insured.getId());
            // A claim cause is offered if at least one of the policy's coverages covers it.
            return policyCoverageResolver.excludedClaimCauseIds(policy.getId());
        } catch (RuntimeException e) {
            log.warn("[ClaimCause] Couldn't resolve coverage exclusions for policy {} — showing the "
                    + "unfiltered list: {}", policyNumber, e.getMessage());
            return Set.of();
        }
    }

    @GetMapping("/all")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Todos los hechos generadores (nombres distintos)",
            description = "Para el filtro 'Tipo de siniestro' de la bandeja, que es global (todos los "
                    + "ramos). Nombres distintos, ordenados.")
    public List<String> all() {
        return claimCauseRepository.findDistinctNames();
    }
}
