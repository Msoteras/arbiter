package ar.edu.utn.frba.arbiter.cases.exceptions;

/** The reply window after closing has run out. The detail is shown as-is to both roles. */
public class ClosedConversationException extends RuntimeException {

    public ClosedConversationException(int windowDays) {
        super("La conversación de este expediente se cerró: sólo se puede escribir hasta "
                + windowDays + " días después de que se resolvió el siniestro.");
    }
}
