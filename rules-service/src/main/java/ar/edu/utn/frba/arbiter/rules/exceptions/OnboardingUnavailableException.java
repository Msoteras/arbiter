package ar.edu.utn.frba.arbiter.rules.exceptions;

/** cases-service didn't answer the checklist's counts: a partial checklist would show wrong steps. */
public class OnboardingUnavailableException extends RuntimeException {

    public OnboardingUnavailableException(Throwable cause) {
        super("No se pudo cargar la puesta en marcha.", cause);
    }
}
