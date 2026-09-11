package ar.edu.utn.frba.arbiter.cases.adapters;

import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;

import java.util.List;
import java.util.Optional;

public interface InsurerAdapter {

    Optional<PolicyResponse> findPolicy(String policyNumber);

    /**
     * @param includeExpired whether policies whose coverage period already ended come back. The
     *                       claim wizard wants them out (picking one only leads to a rejection at
     *                       the end); the insured's profile wants them in, since "this one expired"
     *                       is exactly what they came to check.
     */
    List<PolicyResponse> findPoliciesByInsured(String insuredId, boolean includeExpired);
}
