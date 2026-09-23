package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.Notification;

import java.time.Instant;

/**
 * @param insurerSlug  disambiguates {@code caseId}, since case ids are per schema. Null when the
 *                     caller has a single insurer.
 * @param sentAt       null if the email never went out
 */
public record NotificationResponse(
        Long id,
        Long caseId,
        String insurerSlug,
        String type,
        String content,
        boolean read,
        Instant createdAt,
        Instant sentAt
) {

    public static NotificationResponse from(Notification notification, String insurerSlug) {
        return new NotificationResponse(
                notification.getId(),
                notification.getCaseEntity() == null ? null : notification.getCaseEntity().getId(),
                insurerSlug,
                notification.getType(),
                notification.getContent(),
                notification.isRead(),
                notification.getCreatedAt(),
                notification.getSentAt());
    }
}
