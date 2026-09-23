package ar.edu.utn.frba.arbiter.cases.exceptions;

import java.math.BigDecimal;

/** Cross-field rules on the settlement, which no constraint annotation on a single field can express. */
public class InvalidSettlementException extends RuntimeException {

    private InvalidSettlementException(String message) {
        super(message);
    }

    public static InvalidSettlementException missing() {
        return new InvalidSettlementException(
                "Para aprobar el expediente hay que determinar el monto a pagar.");
    }

    public static InvalidSettlementException notApplicable() {
        return new InvalidSettlementException(
                "Un expediente rechazado no lleva monto a pagar.");
    }

    public static InvalidSettlementException adjustmentWithoutReason() {
        return new InvalidSettlementException(
                "El monto autorizado difiere del calculado: hay que justificar el ajuste.");
    }

    public static InvalidSettlementException notPendingAuthorization(Object status) {
        return new InvalidSettlementException(
                "La liquidación no está esperando autorización (estado actual: " + status + ").");
    }

    public static InvalidSettlementException returnWithoutReason() {
        return new InvalidSettlementException(
                "Para devolver la liquidación hay que indicar el motivo.");
    }

    /** The sum insured is the contractual ceiling per claim; no justification can exceed it. */
    public static InvalidSettlementException aboveSumInsured(BigDecimal amount, BigDecimal sumInsured) {
        return new InvalidSettlementException(
                "El monto autorizado (%s) supera la suma asegurada (%s), que es el límite máximo a indemnizar."
                        .formatted(amount.toPlainString(), sumInsured.toPlainString()));
    }
}
