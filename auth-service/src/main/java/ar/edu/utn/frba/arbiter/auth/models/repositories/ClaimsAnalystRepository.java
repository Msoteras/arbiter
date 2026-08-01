package ar.edu.utn.frba.arbiter.auth.models.repositories;

import ar.edu.utn.frba.arbiter.auth.models.entities.ClaimsAnalyst;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimsAnalystRepository extends JpaRepository<ClaimsAnalyst, Long> {
}
