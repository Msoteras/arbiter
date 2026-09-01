package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * The case's thread no longer takes messages: the reply window after closing has run out. The
 * detail is written for whoever typed the message — both roles see it as-is.
 */
public class ClosedConversationException extends RuntimeException {

    public ClosedConversationException(int windowDays) {
        super("La conversación de este expediente se cerró: sólo se puede escribir hasta "
                + windowDays + " días después de que se resolvió el siniestro.");
    }
}
