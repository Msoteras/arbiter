package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertFirm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExpertFirmRepository extends JpaRepository<ExpertFirm, Long> {

    /**
     * Who the analyst can derive this case to: the specialists in its branch plus the
     * generalists (no branch). Inactive firms are left out — deactivating one is how the
     * referente stops new derivations without erasing the ones already on record.
     */
    @Query("""
            SELECT f FROM ExpertFirm f
            WHERE f.active = true AND (f.branch IS NULL OR f.branch.id = :branchId)
            ORDER BY f.name
            """)
    List<ExpertFirm> findAvailableForBranch(@Param("branchId") Long branchId);
}
