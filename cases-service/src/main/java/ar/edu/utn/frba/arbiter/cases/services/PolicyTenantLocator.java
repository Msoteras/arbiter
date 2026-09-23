package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Which insurer issued the policy a claim is being filed on. The JWT's tenant is resolved at login,
 * before the policy is known, so for someone insured at several insurers it has to come from the
 * policy number.
 *
 * <p>Searches only the schemas in the token's signed {@code insurerIds} claim, never one named by
 * the request. The live insurer DB is asked first: the local snapshot is a cache, not proof of
 * ownership, and a stale or duplicated row there would file the claim under the wrong insurer.
 */
@Service
@RequiredArgsConstructor
public class PolicyTenantLocator {

    private final InsurerRepository insurerRepository;
    private final PolicyRepository policyRepository;
    private final InsurerAdapter insurerAdapter;

    /**
     * @throws UnresolvedCaseReferenceException 422, if none of the caller's insurers has the policy
     */
    public String locate(String policyNumber) {
        List<Long> insurerIds = CallerContext.get().insurerIds();
        if (insurerIds.isEmpty()) {
            // No claim (no user behind the call): keep the tenant already resolved.
            return TenantContext.get();
        }

        List<Insurer> insurers = insurerRepository.findAllById(insurerIds).stream()
                .filter(Insurer::isActive)
                .toList();

        if (insurers.size() == 1) {
            return insurers.get(0).getSchemaName();
        }

        Optional<String> fromInsurer = insurerAdapter.findPolicy(policyNumber)
                .map(PolicyResponse::insurerId)
                .flatMap(insurerId -> insurers.stream()
                        .filter(insurer -> String.valueOf(insurer.getId()).equals(insurerId))
                        .findFirst())
                .map(Insurer::getSchemaName);
        if (fromInsurer.isPresent()) {
            return fromInsurer.get();
        }

        // Best-effort fallback to the local snapshot, for when the insurer DB is unreachable.
        String callerTenant = TenantContext.get();
        try {
            for (Insurer insurer : insurers) {
                TenantContext.set(insurer.getSchemaName());
                if (policyRepository.findByExternalPolicyNumber(policyNumber).isPresent()) {
                    return insurer.getSchemaName();
                }
            }
        } finally {
            // Probing must not leave the tenant switched: the caller decides whether to change it.
            TenantContext.set(callerTenant);
        }

        throw new UnresolvedCaseReferenceException("policy", policyNumber);
    }
}
