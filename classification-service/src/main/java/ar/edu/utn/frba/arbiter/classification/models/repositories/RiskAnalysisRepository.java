package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.models.entities.RiskAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RiskAnalysisRepository extends JpaRepository<RiskAnalysis, Long> {

    Optional<RiskAnalysis> findFirstByCaseIdOrderByIdDesc(Long caseId);
}
