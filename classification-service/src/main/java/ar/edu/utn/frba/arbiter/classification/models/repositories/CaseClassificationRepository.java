package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.models.entities.CaseClassification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CaseClassificationRepository extends JpaRepository<CaseClassification, Long> {

    /**
     * The analyst's decision for a case, reached through the analysis it points at — the
     * decision itself has no case_id, the case is what links to it ({@code cases.classification_id}).
     */
    @Query("""
            SELECT c FROM CaseClassification c
             WHERE c.llmAnalysis.caseId = :caseId
             ORDER BY c.id DESC
             LIMIT 1
            """)
    Optional<CaseClassification> findLatestByCaseId(@Param("caseId") Long caseId);
}
