package ar.edu.utn.frba.arbiter.auth.models.repositories;

import ar.edu.utn.frba.arbiter.auth.models.entities.Insurer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InsurerRepository extends JpaRepository<Insurer, Long> {
}
