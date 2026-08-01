package ar.edu.utn.frba.arbiter.auth.models.repositories;

import ar.edu.utn.frba.arbiter.auth.models.entities.InsurerRepresentative;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InsurerRepresentativeRepository extends JpaRepository<InsurerRepresentative, Long> {
}
