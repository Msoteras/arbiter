package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClaimCauseRepository extends JpaRepository<ClaimCause, Long> {

    /** Hechos generadores de un ramo, para poblar selectores del referente. */
    List<ClaimCause> findByBranch_IdOrderByNameAsc(Long branchId);

    /** Resuelve por nombre dentro del ramo — lo que el motor y el uploader tienen a mano. */
    Optional<ClaimCause> findByBranch_IdAndName(Long branchId, String name);
}
