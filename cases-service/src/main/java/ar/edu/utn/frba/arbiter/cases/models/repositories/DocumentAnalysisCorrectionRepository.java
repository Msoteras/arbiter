package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.DocumentAnalysisCorrection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DocumentAnalysisCorrectionRepository extends JpaRepository<DocumentAnalysisCorrection, Long> {
}
