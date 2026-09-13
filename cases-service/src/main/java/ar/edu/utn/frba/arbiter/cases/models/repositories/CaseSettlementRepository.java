package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.CaseSettlement;
import ar.edu.utn.frba.arbiter.common.enums.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CaseSettlementRepository extends JpaRepository<CaseSettlement, Long> {

    /** At most one per case — {@code case_settlement_case_unique} in the schema. */
    Optional<CaseSettlement> findByCaseId(Long caseId);

    /** The referente's authorization queue. Oldest first: that one has waited longest. */
    List<CaseSettlement> findByStatusOrderByConfirmedAtAsc(SettlementStatus status);

    /**
     * The settlements of a whole page of cases, in one query. Asking case by case is the N+1 that
     * the inbox can't afford — same reason {@code caseAnalysisRepository.findByCaseIds} exists.
     */
    List<CaseSettlement> findByCaseIdIn(Collection<Long> caseIds);
}
