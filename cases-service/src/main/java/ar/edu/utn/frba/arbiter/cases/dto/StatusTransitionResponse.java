package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.CaseStatusHistory;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;

import java.time.Instant;

/** One case status transition, as exposed by the API. {@code fromStatus} is null for the creation row. */
public record StatusTransitionResponse(
        CaseStatus fromStatus,
        CaseStatus toStatus,
        StatusChangeActor actor,
        String reason,
        Instant changedAt
) {
    public static StatusTransitionResponse from(CaseStatusHistory history) {
        return new StatusTransitionResponse(
                history.getFromStatus(),
                history.getToStatus(),
                history.getActor(),
                history.getReason(),
                history.getChangedAt()
        );
    }
}
