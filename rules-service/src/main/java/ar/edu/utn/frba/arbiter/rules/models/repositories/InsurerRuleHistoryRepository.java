package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRuleHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InsurerRuleHistoryRepository extends JpaRepository<InsurerRuleHistory, Long> {
}
