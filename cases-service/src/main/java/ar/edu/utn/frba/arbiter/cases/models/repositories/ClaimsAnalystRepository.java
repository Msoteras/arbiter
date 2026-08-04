package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Reads the analysts of the <b>current tenant</b> — the table is per-schema, so the resolved
 * {@code TenantContext} is what decides whose analysts these are.
 *
 * <p>auth-service owns the alta; this module only points {@code cases.analyst_id} at an existing
 * row. Both read the same table rather than the same REST endpoint because it sits in the same
 * schema as {@code cases}.
 */
@Repository
public interface ClaimsAnalystRepository extends JpaRepository<ClaimsAnalyst, Long> {

    /**
     * Resolves the caller to their analyst profile. The JWT's subject is the email, so this is
     * how "Míos" answers "which analyst am I" without the frontend having to carry a tenant-local
     * id around.
     */
    Optional<ClaimsAnalyst> findByEmail(String email);
}
