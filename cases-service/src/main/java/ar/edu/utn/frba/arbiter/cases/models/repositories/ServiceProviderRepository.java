package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.models.entities.ServiceProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ServiceProviderRepository extends JpaRepository<ServiceProvider, Long> {

    /** Specialists in the branch plus generalists (no branches); inactive providers are left out. */
    @Query("""
            SELECT p FROM ServiceProvider p
            WHERE p.active = true AND p.providerType = :providerType
              AND (p.branches IS EMPTY
                   OR EXISTS (SELECT 1 FROM ServiceProvider sp JOIN sp.branches b
                              WHERE sp = p AND b.id = :branchId))
            ORDER BY p.name
            """)
    List<ServiceProvider> findAvailableForBranch(@Param("branchId") Long branchId,
                                            @Param("providerType") ProviderType providerType);
}
