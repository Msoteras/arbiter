package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CoverageRepository extends JpaRepository<Coverage, Long> {

    List<Coverage> findByBranchIdOrderByNameAsc(Long branchId);

    /** A name is unique per branch only, so this can return one coverage per branch. */
    List<Coverage> findByName(String name);

    /** Branches with no coverage don't appear; callers treat them as zero. */
    @Query("SELECT c.branchId AS branchId, COUNT(c) AS coverageCount FROM Coverage c GROUP BY c.branchId")
    List<BranchCoverageCount> countGroupedByBranch();

    interface BranchCoverageCount {
        Long getBranchId();
        long getCoverageCount();
    }
}
