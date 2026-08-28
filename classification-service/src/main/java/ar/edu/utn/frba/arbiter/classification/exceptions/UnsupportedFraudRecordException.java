package ar.edu.utn.frba.arbiter.classification.exceptions;

/**
 * An {@code EXPERT_BACKED} record arrived without the expert assessment that is supposed to back
 * it. Checked here and not only in cases-service on purpose: this side is the one that later lets
 * the record move a score and veto Fast Track, and "con respaldo pericial" has to mean there is a
 * report to point at, not that someone ticked the stronger of two options.
 */
public class UnsupportedFraudRecordException extends RuntimeException {

    public UnsupportedFraudRecordException(Long caseId) {
        super("El antecedente del expediente " + caseId
                + " se declaró con respaldo pericial pero no referencia ningún peritaje");
    }
}
