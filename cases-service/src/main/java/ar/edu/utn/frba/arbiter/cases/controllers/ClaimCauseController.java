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
 * Catálogo de hechos generadores (claim_cause) por ramo, para poblar el selector del alta de
 * denuncia del asegurado. Los hechos son distintos por ramo (Celulares tiene "Rotura accidental",
 * Tecnología tiene "Daño accidental", etc.), así que el wizard NO puede ofrecer una lista fija:
 * pide los del ramo de la póliza elegida. Por NOMBRE de ramo, que es lo que la póliza tiene a mano.
 * Es el mismo eje que valida {@code CaseReferenceResolver} al crear el caso, así que lo que ofrece
 * el selector siempre resuelve (no más 422 por elegir un hecho que no existe en el ramo).
 *
 * <p>Con {@code policyNumber}, además recorta los hechos que la cobertura de esa póliza excluye
 * ({@code COVERAGE_EXCLUSION}, rules-service) — una lista negra: la cobertura cubre todos los
 * hechos del ramo salvo los marcados ahí. Sin esto, el wizard dejaba elegir "Hurto" sobre una
 * póliza cuya cobertura de Robo no lo cubre, y recién se enteraba en la clasificación, ya subida
 * la documentación.
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
     * Best-effort: an insured typing through the wizard shouldn't lose the whole selector because
     * rules-service hiccuped, or because the policy isn't locally synced yet and the insurer DB is
     * unreachable — those are exactly the failure modes {@link CaseReferenceResolver} already
     * tolerates elsewhere. Worst case, the wizard shows the unfiltered list and
     * {@code CoverageRuleEvaluator} still catches a genuinely excluded hecho during classification.
     */
    private Set<Long> excludedForPolicy(String policyNumber) {
        try {
            String insuredId = CallerContext.get().insuredId();
            if (insuredId == null) {
                return Set.of();
            }
            Insured insured = referenceResolver.resolveInsured(insuredId);
            Policy policy = referenceResolver.resolvePolicy(policyNumber, insured.getId());
            // Unión de lo que cubren TODAS las coberturas de la póliza: un hecho se ofrece si al
            // menos una responde por él. Filtrar por una sola cobertura le escondía al asegurado
            // hechos que su póliza cubre.
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
