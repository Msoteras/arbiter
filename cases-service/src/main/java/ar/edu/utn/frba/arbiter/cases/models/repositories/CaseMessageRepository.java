package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.CaseMessage;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CaseMessageRepository extends JpaRepository<CaseMessage, Long> {

    List<CaseMessage> findByCaseIdOrderByCreatedAtAsc(Long caseId);

    /** {@code senderRole} is the sender's, so callers pass the role that is not theirs. */
    List<CaseMessage> findByCaseIdAndSenderRoleAndReadAtIsNull(Long caseId, StatusChangeActor senderRole);

    /** Drives the "one email per unread streak" rule. */
    boolean existsByCaseIdAndSenderRoleAndReadAtIsNull(Long caseId, StatusChangeActor senderRole);
}
