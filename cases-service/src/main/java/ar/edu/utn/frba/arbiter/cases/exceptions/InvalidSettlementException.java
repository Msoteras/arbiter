package ar.edu.utn.frba.arbiter.cases.exceptions;

import java.math.BigDecimal;

/**
 * The amount the analyst tried to authorize doesn't hold up. Not a validation of one field —
 * every case here is a rule that compares the submitted amount against something else — which is
 * why none of them could live as a constraint annotation on {@code SettlementDecisionRequest}.
 */
public class InvalidSettlementException extends RuntimeException {

    private InvalidSettlementException(String message) {
        super(message);
    }

    /** Approving is also determining how much: the two are one act, not a step and a follow-up. */
    public static InvalidSettlementException missing() {
        return new InvalidSettlementException(
                "Para aprobar el expediente hay que determinar el monto a pagar.");
    }

    /** Rejecting pays nothing, so a settlement in the body is a client bug worth surfacing. */
    public static InvalidSettlementException notApplicable() {
        return new InvalidSettlementException(
                "Un expediente rechazado no lleva monto a pagar.");
    }

    /** Moving the number away from the formula is allowed; doing it silently is not. */
    public static InvalidSettlementException adjustmentWithoutReason() {
        return new InvalidSettlementException(
                "El monto autorizado difiere del calculado: hay que justificar el ajuste.");
    }

    /** Autorizar o devolver algo que no está esperando firma es actuar sobre una foto vieja. */
    public static InvalidSettlementException notPendingAuthorization(Object status) {
        return new InvalidSettlementException(
                "La liquidación no está esperando autorización (estado actual: " + status + ").");
    }

    /** Devolverla sin decir por qué le deja al analista un expediente frenado y ninguna pista. */
    public static InvalidSettlementException returnWithoutReason() {
        return new InvalidSettlementException(
                "Para devolver la liquidación hay que indicar el motivo.");
    }

    /**
     * The sum insured is the contractual ceiling, not a suggestion: "la Suma Asegurada debe
     * entenderse como el límite máximo a indemnizar por cada siniestro" (art. 3, Límites de
     * Indemnización). Below it the analyst can move freely with a justification; above it there is
     * nothing to justify, because the company never undertook to pay it.
     */
    public static InvalidSettlementException aboveSumInsured(BigDecimal amount, BigDecimal sumInsured) {
        return new InvalidSettlementException(
                "El monto autorizado (%s) supera la suma asegurada (%s), que es el límite máximo a indemnizar."
                        .formatted(amount.toPlainString(), sumInsured.toPlainString()));
    }
}
