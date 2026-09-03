package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PolicyCoverageRepository extends JpaRepository<PolicyCoverage, Long> {

    /** Everything the policy contracted, in the order the company lists it. */
    List<PolicyCoverage> findByPolicyIdOrderByDisplayOrderAsc(Long policyId);

    Optional<PolicyCoverage> findByPolicyIdAndCoverageId(Long policyId, Long coverageId);

    void deleteByPolicyId(Long policyId);
}
