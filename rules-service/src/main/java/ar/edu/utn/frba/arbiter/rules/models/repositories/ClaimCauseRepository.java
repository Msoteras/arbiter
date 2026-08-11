package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClaimCauseRepository extends JpaRepository<ClaimCause, Long> {

    /** Hechos generadores de un ramo, para el fan-out de la agenda documental. */
    List<ClaimCause> findByBranch_IdOrderByNameAsc(Long branchId);
}
