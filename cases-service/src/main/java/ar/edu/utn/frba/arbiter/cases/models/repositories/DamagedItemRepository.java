package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.DamagedItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DamagedItemRepository extends JpaRepository<DamagedItem, Long> {
}
