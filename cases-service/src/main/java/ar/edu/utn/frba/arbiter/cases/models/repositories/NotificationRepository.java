package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** The recipient's panel: newest first, which is how a notification list is read. */
    List<Notification> findByRecipientIdOrderByCreatedAtDesc(Long recipientId);

    long countByRecipientIdAndReadFalse(Long recipientId);

    List<Notification> findByRecipientIdAndReadFalse(Long recipientId);

    /**
     * Scoped by recipient on purpose: marking one as read takes the id from the URL, and without
     * this anyone could clear someone else's notifications by guessing ids.
     */
    Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);
}
