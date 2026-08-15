package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CoverageRepository extends JpaRepository<Coverage, Long> {

    /** Coverages of a rama, for the referente's rule-config catalog. Tenant resolved from the JWT. */
    List<Coverage> findByBranchIdOrderByNameAsc(Long branchId);

    /**
     * By name — {@code coverage.name} is UNIQUE within the tenant's schema. It's the only thing
     * that ties the coverage from the insurer DB (which carries name, amount and deductible, but
     * none of our ids) to the one the referente configures, when pulling a policy's snapshot.
     */
    Optional<Coverage> findByName(String name);

    /**
     * One row per branch with at least one coverage — a branch with none simply doesn't appear,
     * same as {@code GROUP BY} always behaves. The referente's ramo list treats "not in this
     * list" as zero, no special-casing needed on the reading side.
     */
    @Query("SELECT c.branchId AS branchId, COUNT(c) AS coverageCount FROM Coverage c GROUP BY c.branchId")
    List<BranchCoverageCount> countGroupedByBranch();

    interface BranchCoverageCount {
        Long getBranchId();
        long getCoverageCount();
    }
}
