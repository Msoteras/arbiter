package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InsurerRuleRepository extends JpaRepository<InsurerRule, Long> {

    Optional<InsurerRule> findFirstByBranch_IdAndCoverageIdAndRuleType(
            Long branchId, Long coverageId, String ruleType);

    /** By coverage alone: a coverage belongs to one branch, and the engine only has the coverage id. */
    Optional<InsurerRule> findFirstByCoverageIdAndRuleType(Long coverageId, String ruleType);

    /** A rule scoped to the whole branch (coverage_id null), such as the free-text rules. */
    Optional<InsurerRule> findFirstByBranch_IdAndCoverageIdIsNullAndRuleType(Long branchId, String ruleType);

    /**
     * The hard temporal rules are one row each, so every evaluation gets its own
     * {@code rule_result.rule_id} and each one can be turned off independently.
     */
    List<InsurerRule> findByBranch_IdAndCoverageIdAndRuleTypeIn(
            Long branchId, Long coverageId, Collection<String> ruleTypes);

    /** Same, by coverage alone: what the engine has at hand on the claim. */
    List<InsurerRule> findByCoverageIdAndRuleTypeIn(Long coverageId, Collection<String> ruleTypes);

    /** A rule scoped to the whole insurer: {@code branch_id} and {@code coverage_id} both null. */
    Optional<InsurerRule> findFirstByBranch_IdIsNullAndCoverageIdIsNullAndRuleType(String ruleType);

    /** Several insurer-wide rules at once — same shape as {@link #findByBranch_IdAndCoverageIdAndRuleTypeIn}. */
    List<InsurerRule> findByBranch_IdIsNullAndCoverageIdIsNullAndRuleTypeIn(Collection<String> ruleTypes);

    /** Every rule with its branch, for the change history, which names the scope of each one. */
    @Query("SELECT r FROM InsurerRule r LEFT JOIN FETCH r.branch")
    List<InsurerRule> findAllForHistory();

    /** The history's type filter: every rule shows at least its creation there. */
    @Query("SELECT DISTINCT r.ruleType FROM InsurerRule r ORDER BY 1")
    List<String> findDistinctRuleTypes();
}
