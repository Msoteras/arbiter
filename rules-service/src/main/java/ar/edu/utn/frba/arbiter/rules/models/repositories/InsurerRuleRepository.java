package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InsurerRuleRepository extends JpaRepository<InsurerRule, Long> {
}
