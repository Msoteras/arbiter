package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InsurerRuleRepository extends JpaRepository<InsurerRule, Long> {

    /**
     * The single rule of a given type for a (rama, cobertura) — the DER scopes a rule by
     * branch + coverage, and Fast Track is modeled as one FAST_TRACK row whose {@code configuration}
     * JSONB holds the thresholds. The tenant schema is resolved from the JWT, so this query already
     * runs against the caller's insurer.
     */
    Optional<InsurerRule> findFirstByBranch_IdAndCoverageIdAndRuleType(
            Long branchId, Long coverageId, String ruleType);

    /**
     * By coverage alone — a coverage id is unique within the tenant and belongs to one rama, so it
     * identifies the rule without the branch. Used by the system-to-system read from
     * classification-service, which carries the claim's coverage id but not the branch id.
     */
    Optional<InsurerRule> findFirstByCoverageIdAndRuleType(Long coverageId, String ruleType);
}
