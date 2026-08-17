package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertAssessment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExpertAssessmentRepository extends JpaRepository<ExpertAssessment, Long> {

    Optional<ExpertAssessment> findByCaseId(Long caseId);

    /** For the inbox: one query for the visible page instead of one per case. */
    List<ExpertAssessment> findByCaseIdIn(List<Long> caseIds);

    /** Whether a catalog entry was ever used — a firm with history is deactivated, not deleted. */
    boolean existsByExpertFirm_Id(Long expertFirmId);
}
