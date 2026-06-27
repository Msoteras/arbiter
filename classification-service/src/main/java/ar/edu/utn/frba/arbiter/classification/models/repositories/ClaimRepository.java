package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.models.entities.Claim;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimRepository extends JpaRepository<Claim, Long> {
}
