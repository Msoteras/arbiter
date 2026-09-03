package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.CaseMessage;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CaseMessageRepository extends JpaRepository<CaseMessage, Long> {

    /** Oldest first: a conversation reads down, and the index is built for exactly this. */
    List<CaseMessage> findByCaseIdOrderByCreatedAtAsc(Long caseId);

    /**
     * What the other side hasn't read yet. {@code senderRole} is the sender's, so the caller asks
     * for the role that is <b>not</b> theirs — the badge counts what came in, never what went out.
     */
    List<CaseMessage> findByCaseIdAndSenderRoleAndReadAtIsNull(Long caseId, StatusChangeActor senderRole);

    /**
     * Whether the recipient still has an unread message on this case. Drives the "one email per
     * unread streak" rule: a second message while the first is unread adds no new email.
     */
    boolean existsByCaseIdAndSenderRoleAndReadAtIsNull(Long caseId, StatusChangeActor senderRole);
}
