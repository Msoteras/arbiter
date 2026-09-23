package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Runs an operation against the tenant an {@code insurer} slug names, restoring the caller's own
 * afterwards.
 *
 * <p>Case ids are sequential per schema, so a case id alone is ambiguous for anyone with policies at
 * more than one insurer. A null or blank slug runs under the tenant already set.
 */
@Component
@RequiredArgsConstructor
public class InsurerTenantScope {

    private final InsurerRepository insurerRepository;

    /**
     * @throws CaseNotFoundException if the slug doesn't match one of the caller's own insurers —
     *         404 rather than 403, so as not to confirm what exists.
     */
    public <T> T forCase(Long caseId, String insurerSlug, Supplier<T> action) {
        if (insurerSlug == null || insurerSlug.isBlank()) {
            return action.get();
        }
        // The slug is request input, so it is matched only against the signed claim: that is what
        // stops a caller from naming another company's tenant.
        Insurer insurer = insurerRepository.findAllById(CallerContext.get().insurerIds()).stream()
                .filter(Insurer::isActive)
                .filter(candidate -> InsurerSlug.matches(candidate, insurerSlug))
                .findFirst()
                .orElseThrow(() -> new CaseNotFoundException(caseId));

        String callerTenant = TenantContext.get();
        try {
            TenantContext.set(insurer.getSchemaName());
            return action.get();
        } finally {
            // Without restoring, the connection returns to the pool on the wrong schema and takes
            // the next request down with it.
            TenantContext.set(callerTenant);
        }
    }
}
