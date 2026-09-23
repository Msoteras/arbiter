package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByRecipientIdAndCreatedAtAfterOrderByCreatedAtDesc(
            Long recipientId, Instant since, Pageable pageable);

    long countByRecipientIdAndReadFalseAndCreatedAtAfter(Long recipientId, Instant since);

    List<Notification> findByRecipientIdAndReadFalse(Long recipientId);

    /** Scoped by recipient so nobody can mark someone else's notifications by guessing ids. */
    Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);

    /**
     * Keeps the daily deadline sweep from re-notifying the same case every morning. Escalating to a
     * new level has a different {@code type}, so it notifies once more.
     */
    boolean existsByCaseEntityIdAndRecipientIdAndType(Long caseId, Long recipientId, String type);
}
