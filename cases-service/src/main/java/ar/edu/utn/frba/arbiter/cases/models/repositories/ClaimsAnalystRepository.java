package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Read-only: auth-service owns this per-tenant table; this module only points {@code cases.analyst_id} at it. */
@Repository
public interface ClaimsAnalystRepository extends JpaRepository<ClaimsAnalyst, Long> {

    /** The JWT subject is the email, so the frontend never carries a tenant-local analyst id. */
    Optional<ClaimsAnalyst> findByEmail(String email);
}
