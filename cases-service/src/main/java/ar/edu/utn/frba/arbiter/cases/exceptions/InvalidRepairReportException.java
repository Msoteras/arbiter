package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * The repair shop's answer doesn't hold together. Both cases compare two fields against each other
 * — the outcome and the quote — so neither could live as a constraint on a single parameter.
 */
public class InvalidRepairReportException extends RuntimeException {

    private InvalidRepairReportException(String message) {
        super(message);
    }

    /** A quote is the amount. Saying one was sent without saying how much answers nothing. */
    public static InvalidRepairReportException quoteWithoutAmount(Long caseId) {
        return new InvalidRepairReportException(
                "El servicio técnico envió un presupuesto para el expediente " + caseId
                        + " pero no se cargó el importe.");
    }

    /** Nothing was fixed, so nobody billed for it: a cost there is a mistake, not a low estimate. */
    public static InvalidRepairReportException costOnAnIrreparableItem(Long caseId) {
        return new InvalidRepairReportException(
                "El expediente " + caseId + " no lleva importe: el servicio técnico declaró el"
                        + " equipo irreparable, así que no hubo arreglo que cobrar.");
    }
}
