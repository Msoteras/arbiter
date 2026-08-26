package ar.edu.utn.frba.arbiter.auth.exceptions;

public class InsuredProfileNotFoundException extends RuntimeException {
    public InsuredProfileNotFoundException(String email) {
        super("No se encontró un perfil de asegurado para " + email);
    }
}
