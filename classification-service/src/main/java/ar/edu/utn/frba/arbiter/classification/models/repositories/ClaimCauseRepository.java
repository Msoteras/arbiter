package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * The branch's claim cause catalog, read straight from {@code arbiter_common} — the entity is
 * shared (common-lib), the repository is this module's, per CLAUDE.md's convention.
 *
 * <p>Read locally and not through rules-service: {@code GET /api/v1/rules/claim-causes} sits behind
 * {@code hasRole('REFERENTE_ASEGURADORA')} and would need a REST hop for a table that lives one
 * schema away.
 */
public interface ClaimCauseRepository extends JpaRepository<ClaimCause, Long> {

    List<ClaimCause> findByBranch_IdOrderByNameAsc(Long branchId);

    List<ClaimCause> findByBranch_NameIgnoreCaseOrderByNameAsc(String branchName);
}
