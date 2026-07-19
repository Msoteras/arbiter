package ar.edu.utn.frba.arbiter.auth.exceptions;

/** Generic on purpose: the login endpoint never reveals whether the email or the password was wrong. */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Email o contraseña inválidos");
    }
}
