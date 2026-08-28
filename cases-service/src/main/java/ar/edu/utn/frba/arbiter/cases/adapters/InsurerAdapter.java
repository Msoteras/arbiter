package ar.edu.utn.frba.arbiter.cases.adapters;

import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;

import java.util.List;
import java.util.Optional;

public interface InsurerAdapter {

    Optional<PolicyResponse> findPolicy(String policyNumber);

    List<PolicyResponse> findPoliciesByInsured(String insuredId);
}
