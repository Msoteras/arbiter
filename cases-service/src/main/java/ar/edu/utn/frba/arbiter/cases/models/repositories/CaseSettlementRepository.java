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

    Optional<CaseSettlement> findByCaseId(Long caseId);

    List<CaseSettlement> findByStatusOrderByConfirmedAtAsc(SettlementStatus status);

    /** {@code authorizedAt} is set only by the referente, so this leaves out amounts that never needed them. */
    List<CaseSettlement> findTop50ByStatusAndAuthorizedAtIsNotNullOrderByAuthorizedAtDesc(
            SettlementStatus status);

    List<CaseSettlement> findByCaseIdIn(Collection<Long> caseIds);
}
