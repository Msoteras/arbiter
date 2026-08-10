package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * The denuncia names an insured other than the one filing it. Only the ASEGURADO can file, and
 * only on their own behalf, so the DNI in the payload has to be the DNI in the token.
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
        super("A denuncia can only be filed on behalf of the insured making the request");
    }
}
