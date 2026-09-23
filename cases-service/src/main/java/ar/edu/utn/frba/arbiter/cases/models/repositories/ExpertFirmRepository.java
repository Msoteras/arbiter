package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertFirm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExpertFirmRepository extends JpaRepository<ExpertFirm, Long> {

    /** Specialists in the branch plus generalists (no branch); inactive firms are left out. */
    @Query("""
            SELECT f FROM ExpertFirm f
            WHERE f.active = true AND f.providerType = :providerType
              AND (f.branch IS NULL OR f.branch.id = :branchId)
            ORDER BY f.name
            """)
    List<ExpertFirm> findAvailableForBranch(@Param("branchId") Long branchId,
                                            @Param("providerType") ProviderType providerType);
}
