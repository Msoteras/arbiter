package ar.edu.utn.frba.arbiter.classification.exceptions;

/**
 * A case can produce one fraud record, not two. The determination is a single act of the analyst
 * on a single file; a second one would either duplicate the person's record or quietly disagree
 * with the first about what was determined.
 */
public class FraudRecordAlreadyExistsException extends RuntimeException {

    public FraudRecordAlreadyExistsException(Long caseId) {
        super("El expediente " + caseId + " ya tiene un antecedente de fraude registrado");
    }
}
