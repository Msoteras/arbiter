package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.CaseStatusHistory;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;

import java.time.Instant;

/** {@code fromStatus} is null for the creation row. */
public record StatusTransitionResponse(
        CaseStatus fromStatus,
        CaseStatus toStatus,
        InsuredStatusResponse fromInsuredStatus,
        InsuredStatusResponse toInsuredStatus,
        StatusChangeActor actor,
        String reason,
        String observation,
        Instant changedAt
) {
    public static StatusTransitionResponse from(CaseStatusHistory history) {
        return new StatusTransitionResponse(
                history.getFromStatus(),
                history.getToStatus(),
                InsuredStatusResponse.from(history.getInitialStatus()),
                InsuredStatusResponse.from(history.getFinalStatus()),
                history.getActor(),
                history.getReason(),
                history.getObservation(),
                history.getChangedAt()
        );
    }

    /**
     * A move the insured can tell happened: their status changed, or they caused it themselves (the
     * creation, a document upload). Assignments and classification retries are neither.
     */
    public boolean visibleToInsured() {
        return fromInsuredStatus == null
                || actor == StatusChangeActor.INSURED
                || fromInsuredStatus.code() != toInsuredStatus.code();
    }

    /**
     * Reason and observation carry the classification, the expert's verdict and the analyst's notes;
     * the internal statuses and who else acted are the insurer's too.
     */
    public StatusTransitionResponse forInsured() {
        return new StatusTransitionResponse(null, null, fromInsuredStatus, toInsuredStatus,
                actor == StatusChangeActor.INSURED ? actor : null, null, null, changedAt);
    }
}
