package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.models.entities.LlmAnalysis;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LlmAnalysisRepository extends JpaRepository<LlmAnalysis, Long> {

    /** Every analysis, oldest first — backs the plain-text results table. */
    @EntityGraph(attributePaths = "reasons")
    List<LlmAnalysis> findAllByOrderByIdAsc();

    /**
     * Latest analysis for a case. Fetches the reasons in the same query — the caller always
     * renders them, and the alternative is an N+1 on a lazy collection outside a session
     * ({@code open-in-view} is off).
     */
    @EntityGraph(attributePaths = "reasons")
    Optional<LlmAnalysis> findFirstByCaseIdOrderByIdDesc(Long caseId);
}
