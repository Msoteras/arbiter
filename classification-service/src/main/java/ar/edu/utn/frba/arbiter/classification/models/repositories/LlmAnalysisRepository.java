package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.models.entities.LlmAnalysis;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LlmAnalysisRepository extends JpaRepository<LlmAnalysis, Long> {

    /** Fetches the reasons eagerly: callers render them outside a session ({@code open-in-view} is off). */
    @EntityGraph(attributePaths = "reasons")
    Optional<LlmAnalysis> findFirstByCaseIdOrderByIdDesc(Long caseId);
}
