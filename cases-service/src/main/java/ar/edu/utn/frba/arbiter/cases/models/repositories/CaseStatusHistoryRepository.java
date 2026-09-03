package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.CaseStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CaseStatusHistoryRepository extends JpaRepository<CaseStatusHistory, Long> {

    List<CaseStatusHistory> findByCaseIdOrderByChangedAtAsc(Long caseId);

    /**
     * When the case last entered the state it is in now. Latest and not first: a case that was
     * reopened and closed again is dated by the closing that still holds.
     */
    Optional<CaseStatusHistory> findFirstByCaseIdAndFinalStatus_IdOrderByChangedAtDesc(
            Long caseId, Long finalStatusId);
}
