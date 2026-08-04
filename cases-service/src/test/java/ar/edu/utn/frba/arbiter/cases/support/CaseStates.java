package ar.edu.utn.frba.arbiter.cases.support;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.CaseState;

/**
 * Builds the {@code arbiter_common.case_status} row a test needs straight from the enum, so a
 * test can keep saying "this case is PENDING_ANALYST_REVIEW" now that the column is an FK.
 *
 * <p>No id: what resolves a state everywhere is {@code name}, and leaving it unset lets a
 * persistence test save the row and get a real one.
 */
public final class CaseStates {

    private CaseStates() {
    }

    public static CaseState of(CaseStatus status) {
        return CaseState.builder()
                .name(status.name())
                .description(status.name())
                .insuredState("En análisis")
                .isFinal(status == CaseStatus.APPROVED || status == CaseStatus.REJECTED)
                .build();
    }
}
