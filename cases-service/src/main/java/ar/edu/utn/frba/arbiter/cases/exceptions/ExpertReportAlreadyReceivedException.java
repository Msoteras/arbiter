package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * The report already came back once. Re-uploading would overwrite a verdict the analyst may
 * have already decided on, and the peritaje is evidence — it does not get replaced quietly.
 */
public class ExpertReportAlreadyReceivedException extends RuntimeException {

    public ExpertReportAlreadyReceivedException(Long caseId) {
        super("The expert report for case " + caseId + " was already received");
    }
}
