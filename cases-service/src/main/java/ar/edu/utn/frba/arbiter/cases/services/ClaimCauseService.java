package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Claim causes are looked up by branch name, the same key {@code CaseReferenceResolver} validates
 * against, so whatever the selector offers resolves. With a policy number, causes excluded by the
 * policy's coverages ({@code COVERAGE_EXCLUSION}) are removed.
 *
 * <p>The catalog is per insurer, so with a policy number the lookup runs in the schema of the
 * insurer that issued it: someone insured at two companies is logged in under only one of them, and
 * the other's branch names may not even exist there.
 *
 * <p>Deliberately not {@code @Transactional}: the coverage lookup is best-effort and a failure
 * inside it must not mark an enclosing transaction rollback-only.
 */
@Service
@RequiredArgsConstructor
public class ClaimCauseService {

    private static final Logger log = LoggerFactory.getLogger(ClaimCauseService.class);

    private final ClaimCauseRepository claimCauseRepository;
    private final CaseReferenceResolver referenceResolver;
    private final PolicyCoverageResolver policyCoverageResolver;
    private final PolicyTenantLocator policyTenantLocator;

    public List<String> namesByBranch(String branch, String policyNumber) {
        if (policyNumber == null) {
            return names(branch, Set.of());
        }
        String callerTenant = TenantContext.get();
        try {
            TenantContext.set(issuingTenant(policyNumber, callerTenant));
            return names(branch, excludedForPolicy(policyNumber));
        } finally {
            TenantContext.set(callerTenant);
        }
    }

    private List<String> names(String branch, Set<Long> excluded) {
        return claimCauseRepository.findByBranch_NameOrderByNameAsc(branch).stream()
                .filter(c -> !excluded.contains(c.getId()))
                .map(ClaimCause::getName)
                .toList();
    }

    /** Best-effort like the exclusions: a policy that can't be located keeps the caller's schema. */
    private String issuingTenant(String policyNumber, String callerTenant) {
        try {
            return policyTenantLocator.locate(policyNumber);
        } catch (RuntimeException e) {
            log.warn("[ClaimCause] Couldn't locate the insurer of policy {} — reading the caller's "
                    + "catalog: {}", policyNumber, e.getMessage());
            return callerTenant;
        }
    }

    public List<String> allDistinctNames() {
        return claimCauseRepository.findDistinctNames();
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
}
