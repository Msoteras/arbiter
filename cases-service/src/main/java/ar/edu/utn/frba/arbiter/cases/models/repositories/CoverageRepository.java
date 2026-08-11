package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CoverageRepository extends JpaRepository<Coverage, Long> {

    /** Coverages of a rama, for the referente's rule-config catalog. Tenant resolved from the JWT. */
    List<Coverage> findByBranchIdOrderByNameAsc(Long branchId);
}
