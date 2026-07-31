package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerClaimCause;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InsurerClaimCauseRepository extends JpaRepository<InsurerClaimCause, Long> {
}
