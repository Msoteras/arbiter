package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimCauseRepository extends JpaRepository<ClaimCause, Long> {
}
