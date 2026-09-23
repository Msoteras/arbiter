package ar.edu.utn.frba.arbiter.classification.exceptions;

/**
 * An {@code EXPERT_BACKED} record without an expert assessment. Checked here too, not only in
 * cases-service, because this side lets the record move the score and veto Fast Track.
 */
public class UnsupportedFraudRecordException extends RuntimeException {

    public UnsupportedFraudRecordException(Long caseId) {
        super("El antecedente del expediente " + caseId
                + " se declaró con respaldo pericial pero no referencia ningún peritaje");
    }
}
