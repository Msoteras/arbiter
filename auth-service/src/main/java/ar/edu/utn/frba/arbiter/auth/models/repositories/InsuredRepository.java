package ar.edu.utn.frba.arbiter.auth.models.repositories;

import ar.edu.utn.frba.arbiter.auth.models.entities.Insured;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InsuredRepository extends JpaRepository<Insured, Long> {
}
