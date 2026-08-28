package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.CaseState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseStateRepository extends JpaRepository<CaseState, Long> {
}
