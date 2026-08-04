package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.ScoringConfigurationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScoringConfigurationHistoryRepository extends JpaRepository<ScoringConfigurationHistory, Long> {
}
