package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.models.entities.CaseClassification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseClassificationRepository extends JpaRepository<CaseClassification, Long> {
}
