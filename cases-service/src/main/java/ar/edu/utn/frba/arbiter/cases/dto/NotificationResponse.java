package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.Notification;

import java.time.Instant;

/**
 * A notification as the insured's panel shows it.
 *
 * @param caseId what the panel links to, so the notification is actionable and not just a note
 * @param sentAt when the email went out; null if it never did (the table has no creation
 *               timestamp, so this is the only date available — see the story's pending items)
 */
public record NotificationResponse(
        Long id,
        Long caseId,
        String type,
        String content,
        boolean read,
        Instant sentAt
) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getCaseEntity() == null ? null : notification.getCaseEntity().getId(),
                notification.getType(),
                notification.getContent(),
                notification.isRead(),
                notification.getSentAt());
    }
}
