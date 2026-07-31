package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Coverage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoverageRepository extends JpaRepository<Coverage, Long> {
}
