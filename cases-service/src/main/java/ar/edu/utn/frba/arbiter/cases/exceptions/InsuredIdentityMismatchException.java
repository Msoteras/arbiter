package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * An insured may only act on their own behalf, so a DNI in the request must match the token. The
 * check never touches the table, so nothing leaks; the DNI stays out of the message (Ley 25.326).
 */
public class InsuredIdentityMismatchException extends RuntimeException {

    public InsuredIdentityMismatchException() {
        this("A denuncia can only be filed on behalf of the insured making the request");
    }

    public InsuredIdentityMismatchException(String message) {
        super(message);
    }
}
