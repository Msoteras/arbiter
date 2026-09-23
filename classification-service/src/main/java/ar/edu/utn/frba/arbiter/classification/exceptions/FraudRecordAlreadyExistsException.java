package ar.edu.utn.frba.arbiter.classification.exceptions;

/** A case produces at most one fraud record: a second would duplicate or contradict the first. */
public class FraudRecordAlreadyExistsException extends RuntimeException {

    public FraudRecordAlreadyExistsException(Long caseId) {
        super("El expediente " + caseId + " ya tiene un antecedente de fraude registrado");
    }
}
