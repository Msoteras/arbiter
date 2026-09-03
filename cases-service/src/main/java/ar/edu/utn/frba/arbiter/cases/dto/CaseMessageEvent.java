package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.CaseMessage;

import java.time.Instant;

/**
 * A message as pushed over the socket. Not {@link CaseMessageResponse}: that one carries
 * {@code mine}, which is per-viewer, and a broadcast has one payload for both sides.
 */
public record CaseMessageEvent(
        Long id,
        Long caseId,
        String sender,
        String body,
        Instant createdAt
) {

    public static CaseMessageEvent from(CaseMessage message) {
        return new CaseMessageEvent(
                message.getId(),
                message.getCaseId(),
                message.getSenderRole().name(),
                message.getBody(),
                message.getCreatedAt());
    }
}
