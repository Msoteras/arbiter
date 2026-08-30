package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.CaseMessage;

import java.time.Instant;

/**
 * One message as either side's thread shows it.
 *
 * <p>No sender name on purpose: the insured is talking to the insurer's claims desk, not to a
 * person, and cases get reassigned. The analyst already has the insured's name on the case header.
 *
 * @param mine whether the caller wrote it, resolved server-side so neither client has to know the
 *             caller's role to lay out the thread
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
