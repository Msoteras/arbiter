package ar.edu.utn.frba.arbiter.cases.exceptions;

/** Why an analyst can't move a case to another claim cause. */
public class ClaimCauseCorrectionNotAllowedException extends RuntimeException {

    private ClaimCauseCorrectionNotAllowedException(String message) {
        super(message);
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
