package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.ScoreBand;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScoreBandRepository extends JpaRepository<ScoreBand, Long> {
}
