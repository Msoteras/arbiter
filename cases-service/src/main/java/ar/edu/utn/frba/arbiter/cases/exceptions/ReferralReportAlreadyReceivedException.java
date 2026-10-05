package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * The report already came back once. Re-uploading would overwrite a verdict the analyst may
 * have already decided on, and the peritaje is evidence — it does not get replaced quietly.
 */
public class ReferralReportAlreadyReceivedException extends RuntimeException {

    public ReferralReportAlreadyReceivedException(Long caseId) {
        super("The provider report for case " + caseId + " was already received");
    }
}
