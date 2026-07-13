package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.CaseStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CaseStatusHistoryRepository extends JpaRepository<CaseStatusHistory, Long> {

    List<CaseStatusHistory> findByCaseIdOrderByChangedAtAsc(Long caseId);
}
