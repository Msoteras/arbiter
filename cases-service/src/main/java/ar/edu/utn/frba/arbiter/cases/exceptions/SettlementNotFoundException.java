package ar.edu.utn.frba.arbiter.cases.exceptions;

public class SettlementNotFoundException extends RuntimeException {

    public SettlementNotFoundException(Long caseId) {
        super("El expediente " + caseId + " no tiene liquidación determinada.");
    }
}
