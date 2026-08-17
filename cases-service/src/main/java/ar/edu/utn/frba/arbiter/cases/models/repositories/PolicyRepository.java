package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PolicyRepository extends JpaRepository<Policy, Long> {

    /** {@code external_policy_number} is UNIQUE — it's how the denuncia names a policy. */
    Optional<Policy> findByExternalPolicyNumber(String externalPolicyNumber);

    /**
     * Coverage id + owner DNI of a policy in a single round-trip, for the covered-claim-causes lookup
     * of the claim wizard. Folds what used to be two remote queries (re-fetch the policy + look up its
     * insured for the ownership check) into one — the DB Arbiter lives behind a WAN link, so each
     * saved round-trip is ~1s. Left joins so a policy with no coverage (null id) or an orphan insured
     * still returns its row instead of vanishing.
     */
    @Query("""
            select c.id as coverageId, i.dni as ownerDni
            from Policy p
            left join p.coverage c
            left join Insured i on i.id = p.insuredId
            where p.externalPolicyNumber = :policyNumber
            """)
    Optional<PolicyCoverageOwner> findCoverageAndOwner(@Param("policyNumber") String policyNumber);

    /** Projection for {@link #findCoverageAndOwner}: both values may be null (see the query's joins). */
    interface PolicyCoverageOwner {
        Long getCoverageId();

        String getOwnerDni();
    }
}
