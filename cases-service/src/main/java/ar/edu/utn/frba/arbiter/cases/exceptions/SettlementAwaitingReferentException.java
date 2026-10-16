package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * The analyst already approved and the amount waits for the referent: a new decision or a closure
 * would leave that settlement in the referent's queue with no case to resolve.
 */
public class SettlementAwaitingReferentException extends RuntimeException {

    public SettlementAwaitingReferentException(Long caseId) {
        super("La liquidación está esperando la firma del referente.");
    }
}
