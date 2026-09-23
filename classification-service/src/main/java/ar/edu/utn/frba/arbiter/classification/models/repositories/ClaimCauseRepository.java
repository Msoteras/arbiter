package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Read from {@code arbiter_common} directly: rules-service's endpoint is referente-only. */
public interface ClaimCauseRepository extends JpaRepository<ClaimCause, Long> {

    List<ClaimCause> findByBranch_IdOrderByNameAsc(Long branchId);

    List<ClaimCause> findByBranch_NameIgnoreCaseOrderByNameAsc(String branchName);
}
