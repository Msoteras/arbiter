package ar.edu.utn.frba.arbiter.cases.exceptions;

import java.math.BigDecimal;

/**
 * The insurer's rule doesn't allow deriving this case. Enforced server-side and not only by
 * hiding the button: a threshold that lives in the frontend is a suggestion, not a rule.
 */
public class DerivationNotAllowedException extends RuntimeException {

    public DerivationNotAllowedException(Long caseId) {
        super("La aseguradora no deriva a peritaje los siniestros de este ramo (expediente " + caseId + ")");
    }

    public DerivationNotAllowedException(Long caseId, BigDecimal claimedAmount, BigDecimal minClaimedAmount) {
        super("El monto reclamado del expediente " + caseId + " (" + claimedAmount + ") no alcanza el "
                + "mínimo configurado para derivar a peritaje (" + minClaimedAmount + ")");
    }
}
