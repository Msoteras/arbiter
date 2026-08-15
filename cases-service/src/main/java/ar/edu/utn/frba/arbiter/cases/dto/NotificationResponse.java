package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.Notification;

import java.time.Instant;

/**
 * A notification as the insured's panel shows it.
 *
 * @param caseId    what the panel links to, so the notification is actionable and not just a note
 * @param createdAt what the panel dates it by — always set, unlike {@code sentAt}
 * @param sentAt    when the email went out; null if it never did
 */
public record NotificationResponse(
        Long id,
        Long caseId,
        String type,
        String content,
        boolean read,
        Instant createdAt,
        Instant sentAt
) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getCaseEntity() == null ? null : notification.getCaseEntity().getId(),
                notification.getType(),
                notification.getContent(),
                notification.isRead(),
                notification.getCreatedAt(),
                notification.getSentAt());
    }
}
