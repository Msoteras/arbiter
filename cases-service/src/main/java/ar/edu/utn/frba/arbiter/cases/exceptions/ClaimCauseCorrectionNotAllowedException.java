package ar.edu.utn.frba.arbiter.cases.exceptions;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;

/** Why an analyst can't move a case to another claim cause. */
public class ClaimCauseCorrectionNotAllowedException extends RuntimeException {

    private ClaimCauseCorrectionNotAllowedException(String message) {
        super(message);
    }

    /** Mid-reclassification is the common case: the analyst just corrected it and tried again. */
    public static ClaimCauseCorrectionNotAllowedException notUnderReview(CaseStatus status) {
        return new ClaimCauseCorrectionNotAllowedException(status == CaseStatus.PENDING_CLASSIFICATION
                ? "Este expediente se está reclasificando. Esperá a que termine para corregirlo."
                : "Solo se puede corregir el hecho generador mientras el expediente está en revisión.");
    }

    public static ClaimCauseCorrectionNotAllowedException sameCause() {
        return new ClaimCauseCorrectionNotAllowedException(
                "El expediente ya tiene ese hecho generador y esa cobertura.");
    }

    /** A cause of another branch would need another policy, not a correction. */
    public static ClaimCauseCorrectionNotAllowedException otherBranch() {
        return new ClaimCauseCorrectionNotAllowedException(
                "El hecho generador tiene que ser del mismo ramo que el expediente.");
    }

    public static ClaimCauseCorrectionNotAllowedException notCovered(String claimCause) {
        return new ClaimCauseCorrectionNotAllowedException(
                "Ninguna cobertura de la póliza cubre «" + claimCause + "».");
    }

    /** The referent is judging an amount computed for the current coverage. */
    public static ClaimCauseCorrectionNotAllowedException settlementAwaitingReferent() {
        return new ClaimCauseCorrectionNotAllowedException(
                "La liquidación está esperando la firma del referente.");
    }
}
