package ar.edu.utn.frba.arbiter.cases.exceptions;

import ar.edu.utn.frba.arbiter.cases.dto.RepairOutcome;

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

    /** Repaired or irreparable: there is no quote behind either, so a number there is a mistake. */
    public static InvalidRepairReportException amountWithoutQuote(Long caseId, RepairOutcome outcome) {
        return new InvalidRepairReportException(
                "El expediente " + caseId + " no lleva presupuesto: el servicio técnico respondió "
                        + outcome + ".");
    }
}
