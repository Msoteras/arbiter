package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** The panel's window, newest first and capped by the pageable. */
    List<Notification> findByRecipientIdAndCreatedAtAfterOrderByCreatedAtDesc(
            Long recipientId, Instant since, Pageable pageable);

    long countByRecipientIdAndReadFalseAndCreatedAtAfter(Long recipientId, Instant since);

    List<Notification> findByRecipientIdAndReadFalse(Long recipientId);

    /**
     * Scoped by recipient on purpose: marking one as read takes the id from the URL, and without
     * this anyone could clear someone else's notifications by guessing ids.
     */
    Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);

    /**
     * Whether this recipient was already told about this case at this level. The deadline sweep
     * runs daily, so without this it would re-notify the same critical case every morning. A case
     * escalating from CRITICAL to OVERDUE has a different {@code type}, so it notifies once more —
     * the intended escalation, not a duplicate.
     */
    boolean existsByCaseEntityIdAndRecipientIdAndType(Long caseId, Long recipientId, String type);
}
