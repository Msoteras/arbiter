package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClaimCauseRepository extends JpaRepository<ClaimCause, Long> {

    /** A branch's claim causes, for the document schedule's fan-out. */
    List<ClaimCause> findByBranch_IdOrderByNameAsc(Long branchId);

    /** Resolves a claim cause by name within a branch — what the document schedule reads it by. */
    Optional<ClaimCause> findByBranch_IdAndName(Long branchId, String name);
}
