package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
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

    /**
     * A rule scoped to the whole rama, no cobertura (coverage_id null — valid per the DER, see
     * InsurerRule's javadoc). Used for the free-text rules that have no table of their own
     * (commonExclusions, businessRules): they apply to the ramo as a whole.
     */
    Optional<InsurerRule> findFirstByBranch_IdAndCoverageIdIsNullAndRuleType(Long branchId, String ruleType);

    /**
     * Several rules of the same (branch, coverage) at once — the hard temporal rules, which are
     * one row per rule rather than one row with everything inside (so each evaluation has its own
     * {@code rule_result.rule_id} and the referente can turn one off without touching the others).
     */
    List<InsurerRule> findByBranch_IdAndCoverageIdAndRuleTypeIn(
            Long branchId, Long coverageId, Collection<String> ruleTypes);

    /** Same, by coverage alone: what the engine has at hand on the claim. */
    List<InsurerRule> findByCoverageIdAndRuleTypeIn(Long coverageId, Collection<String> ruleTypes);

    /**
     * A rule scoped to the whole insurer — {@code branch_id} and {@code coverage_id} both null
     * (valid per the DER, see InsurerRule's javadoc). Used by {@code RuleType#insurerScoped()}
     * (policy in force, arrears): the schema already identifies the insurer, so no branch/coverage
     * is needed to find the one row.
     */
    Optional<InsurerRule> findFirstByBranch_IdIsNullAndCoverageIdIsNullAndRuleType(String ruleType);

    /** Several insurer-wide rules at once — same shape as {@link #findByBranch_IdAndCoverageIdAndRuleTypeIn}. */
    List<InsurerRule> findByBranch_IdIsNullAndCoverageIdIsNullAndRuleTypeIn(Collection<String> ruleTypes);
}
