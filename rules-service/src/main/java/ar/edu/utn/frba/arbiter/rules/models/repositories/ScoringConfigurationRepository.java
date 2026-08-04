package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.ScoringConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScoringConfigurationRepository extends JpaRepository<ScoringConfiguration, Long> {
}
