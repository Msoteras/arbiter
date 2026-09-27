package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.InsuredIdentityMismatchException;
import ar.edu.utn.frba.arbiter.cases.exceptions.PolicyNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The insured's view of their policies across all their insurers. The DNI arrives as a request
 * parameter, so it is always checked against the token's: an insured only sees their own policies.
 */
@Service
@RequiredArgsConstructor
public class PolicyService {

    private final InsurerAdapter insurerAdapter;

    /**
     * @param includeExpired false for claim filing (only policies that pass eligibility); true for the
     *                       profile, where hiding an expired policy would confuse the insured
     */
    public List<PolicyResponse> listByInsured(String insuredId, boolean includeExpired) {
        assertOwnPolicies(insuredId);
        return insurerAdapter.findPoliciesByInsured(insuredId, includeExpired);
    }

    public PolicyResponse getByNumber(String policyNumber) {
        PolicyResponse policy = insurerAdapter.findPolicy(policyNumber)
                .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
        // 404 rather than 403 on someone else's policy: a 403 would confirm the number exists.
        if (!isOwn(policy.insuredId())) {
            throw new PolicyNotFoundException(policyNumber);
        }
        return policy;
    }

    /**
     * The insurer contact has no DNI in the token: their scope is the set of schemas they can read.
     * For an insured, the token's DNI is the only scope.
     */
    private void assertOwnPolicies(String requestedInsuredId) {
        if (!isOwn(requestedInsuredId)) {
            throw new InsuredIdentityMismatchException(
                    "An insured can only list their own policies");
        }
    }

    private boolean isOwn(String insuredId) {
        String callerDni = CallerContext.get().insuredId();
        return callerDni == null || callerDni.equals(insuredId);
    }
}
