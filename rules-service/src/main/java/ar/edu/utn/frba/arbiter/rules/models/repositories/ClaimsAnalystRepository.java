package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read-only, for the setup checklist. The table's owner is auth-service. */
public interface ClaimsAnalystRepository extends JpaRepository<ClaimsAnalyst, Long> {

    /** Invited analysts who haven't chosen a password yet. */
    long countByUser_ActivatedFalse();
}
