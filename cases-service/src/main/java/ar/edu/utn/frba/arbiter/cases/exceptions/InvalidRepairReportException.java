package ar.edu.utn.frba.arbiter.cases.exceptions;

/** Cross-field checks between outcome and quote, which no single-parameter constraint can express. */
public class InvalidRepairReportException extends RuntimeException {

    private InvalidRepairReportException(String message) {
        super(message);
    }

    public static InvalidRepairReportException quoteWithoutAmount(Long caseId) {
        return new InvalidRepairReportException(
                "El servicio técnico envió un presupuesto para el expediente " + caseId
                        + " pero no se cargó el importe.");
    }

    public static InvalidRepairReportException costOnAnIrreparableItem(Long caseId) {
        return new InvalidRepairReportException(
                "El expediente " + caseId + " no lleva importe: el servicio técnico declaró el"
                        + " equipo irreparable, así que no hubo arreglo que cobrar.");
    }
}
