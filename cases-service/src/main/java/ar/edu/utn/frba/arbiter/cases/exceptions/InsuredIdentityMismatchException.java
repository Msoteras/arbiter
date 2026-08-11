package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * The request names an insured other than the one making it. An ASEGURADO acts only on their own
 * behalf — filing a denuncia, listing their policies — so a DNI that arrives in a payload or a
 * query param has to be the DNI in the token.
 *
 * <p>403 and not 404: the caller is asserting a false identity about themselves, which is a
 * permission problem, not a lookup that failed. Nothing about whether that DNI exists leaks —
 * the check compares against the token, never against the table.
 *
 * <p>The DNI is deliberately kept out of the message: it is someone else's personal data
 * (Ley 25.326) and echoing it back would hand it to whoever probed for it.
 */
public class InsuredIdentityMismatchException extends RuntimeException {

    public InsuredIdentityMismatchException() {
        this("A denuncia can only be filed on behalf of the insured making the request");
    }

    public InsuredIdentityMismatchException(String message) {
        super(message);
    }
}
