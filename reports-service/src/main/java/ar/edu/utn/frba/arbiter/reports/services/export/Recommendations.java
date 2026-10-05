package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.common.enums.Classification;

final class Recommendations {

    private Recommendations() {
    }

    /**
     * Only the two actionable recommendations can be departed from. Fast Track is the rules gate and
     * the other two point nowhere, so they are left out rather than counted as agreement.
     *
     * @return null when the recommendation was not one a decision could depart from
     */
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

    private static Boolean approved(String decision) {
        return switch (decision) {
            case "APPROVE", "APROBAR" -> true;
            case "REJECT", "RECHAZAR" -> false;
            default -> null;
        };
    }
}
