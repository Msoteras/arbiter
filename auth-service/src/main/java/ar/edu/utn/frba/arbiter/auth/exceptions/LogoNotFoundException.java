package ar.edu.utn.frba.arbiter.auth.exceptions;

public class LogoNotFoundException extends RuntimeException {
    public LogoNotFoundException() {
        super("La aseguradora no tiene logo.");
    }
}
