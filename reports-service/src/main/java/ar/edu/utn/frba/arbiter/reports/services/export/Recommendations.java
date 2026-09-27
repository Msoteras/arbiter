package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.common.enums.Classification;

/**
 * Whether the analyst decided the way the model pointed.
 *
 * <p>Only the two actionable recommendations can be departed from. Fast Track is the deterministic
 * gate and not the model's opinion; missing documentation and a request for manual review point
 * nowhere, so there is nothing for a decision to agree or disagree with — those cases are left out
 * of the count rather than scored as agreement, which would inflate it.
 *
 * <p>Nothing here judges the analyst. Decisions are theirs to make and a departure is a legitimate
 * outcome; the report surfaces it because it is the row worth reading, not because it is wrong.
 */
final class Recommendations {

    private Recommendations() {
    }

    /** @return null when the recommendation was not one a decision could depart from */
    static Boolean followed(Classification classification, String decision) {
        if (classification == null || decision == null) {
            return null;
        }
        Boolean approved = approved(decision);
        if (approved == null) {
            return null;
        }
        return switch (classification) {
            case LLM_RECOMIENDA_APROBAR -> approved;
            case LLM_NO_RECOMIENDA_APROBAR -> !approved;
            case FAST_TRACK, FALTA_DOCUMENTACION, LLM_SOLICITA_REVISION_MANUAL -> null;
        };
    }

    /** Both spellings are in the data: APPROVE/REJECT from the app, APROBAR/RECHAZAR from older rows. */
    private static Boolean approved(String decision) {
        return switch (decision) {
            case "APPROVE", "APROBAR" -> true;
            case "REJECT", "RECHAZAR" -> false;
            default -> null;
        };
    }
}
