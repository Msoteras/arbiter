package ar.edu.utn.frba.arbiter.auth.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClaimsAnalystRepository extends JpaRepository<ClaimsAnalyst, Long> {

    Optional<ClaimsAnalyst> findByUserId(Long userId);

    /**
     * The request insurer's analysts — the table is per-schema, so the resolved tenant already
     * decides whose they are. Sorted by surname and name: it's a list to pick a person from,
     * not a log.
     */
    List<ClaimsAnalyst> findAllByOrderBySurnameAscNameAsc();
}
