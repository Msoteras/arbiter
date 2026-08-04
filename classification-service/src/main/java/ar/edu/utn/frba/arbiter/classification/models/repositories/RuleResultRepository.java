package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.models.entities.RuleResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleResultRepository extends JpaRepository<RuleResult, Long> {
}
