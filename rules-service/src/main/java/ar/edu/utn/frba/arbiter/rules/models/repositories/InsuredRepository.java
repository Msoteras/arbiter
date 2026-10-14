package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Read-only, for the setup checklist. The table's owner is auth-service, which only creates a row
 * together with the insured's account.
 */
public interface InsuredRepository extends JpaRepository<Insured, Long> {}
