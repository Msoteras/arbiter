package ar.edu.utn.frba.arbiter.cases.adapters;

import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;

import java.util.List;
import java.util.Optional;

public interface InsurerAdapter {

    Optional<PolicyResponse> findPolicy(String policyNumber);

    /**
     * @param includeExpired false for the claim wizard (an expired policy only leads to a rejection);
     *                       true for the insured's profile, where seeing it expired is the point.
     */
    List<PolicyResponse> findPoliciesByInsured(String insuredId, boolean includeExpired);

    /**
     * Distinct coverage names on the resolved tenant's policies of a branch, spelled as the insurer
     * database spells them: they are what a coverage in Arbiter has to be called to match.
     *
     * @param branchExternalName the branch as the insurer database names it ({@code poliza.rama})
     */
    List<String> findCoverageNames(String branchExternalName);
}
