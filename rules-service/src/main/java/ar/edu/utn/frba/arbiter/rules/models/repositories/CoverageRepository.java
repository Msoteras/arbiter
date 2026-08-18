package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Read-only, and only to resolve which branch a coverage belongs to: the text rules are stored by
 * branch, but the classification engine only has a {@code coverageId} at hand. The table's
 * functional owner is still cases-service.
 */
public interface CoverageRepository extends JpaRepository<Coverage, Long> {
}
