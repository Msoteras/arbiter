package ar.edu.utn.frba.arbiter.siniestros.exceptions;

public class ClasificacionInvalidaException extends RuntimeException {

    public ClasificacionInvalidaException(String mensaje) {
        super(mensaje);
    }

    public ClasificacionInvalidaException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
