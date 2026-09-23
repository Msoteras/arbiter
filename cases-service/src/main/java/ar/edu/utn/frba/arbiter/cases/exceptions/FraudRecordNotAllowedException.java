package ar.edu.utn.frba.arbiter.cases.exceptions;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;

public class FraudRecordNotAllowedException extends RuntimeException {

    /** The case hasn't reached — or has moved past — the point where the analyst determines this. */
    public FraudRecordNotAllowedException(Long caseId, CaseStatus status) {
        super("El expediente " + caseId + " está en estado " + status
                + " y no admite registrar un antecedente de fraude");
    }

    /** {@code EXPERT_BACKED} was claimed on a case whose expert report doesn't confirm the fraud. */
    public FraudRecordNotAllowedException(Long caseId) {
        super("El expediente " + caseId + " no tiene un peritaje con fraude confirmado: el antecedente "
                + "con respaldo pericial exige un informe que lo respalde");
    }
}
