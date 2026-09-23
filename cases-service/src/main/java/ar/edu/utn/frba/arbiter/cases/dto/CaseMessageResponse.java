package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.CaseMessage;

import java.time.Instant;

/**
 * No sender name on purpose: the insured talks to the claims desk, not a person, and cases get reassigned.
 *
 * @param mine resolved server-side so clients don't need the caller's role to lay out the thread
 */
public record CaseMessageResponse(
        Long id,
        String sender,
        boolean mine,
        String body,
        Instant createdAt,
        Instant readAt
) {

    public static CaseMessageResponse from(CaseMessage message, boolean mine) {
        return new CaseMessageResponse(
                message.getId(),
                message.getSenderRole().name(),
                mine,
                message.getBody(),
                message.getCreatedAt(),
                message.getReadAt());
    }
}
