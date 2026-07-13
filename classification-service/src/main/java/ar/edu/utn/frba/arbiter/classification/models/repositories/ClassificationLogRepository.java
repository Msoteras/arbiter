package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.models.entities.ClassificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClassificationLogRepository extends JpaRepository<ClassificationLog, Long> {

    List<ClassificationLog> findAllByOrderByIdAsc();

    Optional<ClassificationLog> findFirstByCaseIdOrderByIdDesc(Long caseId);
}
