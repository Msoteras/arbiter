package ar.edu.utn.frba.arbiter.cases.support;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.InsuredStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.CaseState;

/**
 * Builds the {@code arbiter_common.case_status} row a test needs straight from the enum.
 *
 * <p>No id: states are resolved by {@code name}, and leaving it unset lets a persistence test
 * save the row and get a real one.
 */
public final class CaseStates {

    private CaseStates() {
    }

    public static CaseState of(CaseStatus status) {
        return CaseState.builder()
                .name(status.name())
                .description(status.name())
                .insuredState("En análisis")
                .insuredStatusCode(insuredStatusOf(status))
                .isFinal(CaseStatus.terminal().contains(status))
                .build();
    }

    /** Same mapping as the seed in {@code db/init-multitenant.sql}. */
    private static InsuredStatus insuredStatusOf(CaseStatus status) {
        return switch (status) {
            case PENDING_CLASSIFICATION, PENDING_ANALYST_REVIEW, CLASSIFICATION_FAILED ->
                    InsuredStatus.UNDER_ANALYSIS;
            case AWAITING_DOCUMENTATION -> InsuredStatus.AWAITING_DOCUMENTATION;
            case PENDING_EXPERT_REPORT -> InsuredStatus.UNDER_VERIFICATION;
            case PENDING_REPAIR -> InsuredStatus.UNDER_REPAIR;
            case APPROVED -> InsuredStatus.APPROVED;
            case REJECTED, PRESCRIBED -> InsuredStatus.REJECTED;
            case LAPSED -> InsuredStatus.LAPSED;
            case WITHDRAWN, VOIDED -> InsuredStatus.CLOSED;
        };
    }
}
