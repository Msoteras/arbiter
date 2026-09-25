package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.models.entities.LlmAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LlmAnalysisRepository extends JpaRepository<LlmAnalysis, Long> {

    /** Fetches the reasons eagerly: callers render them outside a session ({@code open-in-view} is off). */
    @Query("""
            SELECT a FROM LlmAnalysis a
              LEFT JOIN FETCH a.reasons r
             WHERE a.id = (SELECT MAX(a2.id) FROM LlmAnalysis a2 WHERE a2.caseId = :caseId)
             ORDER BY r.id
            """)
    Optional<LlmAnalysis> findLatestByCaseId(@Param("caseId") Long caseId);
}
