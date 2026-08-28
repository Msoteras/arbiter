package ar.edu.utn.frba.arbiter.auth.exceptions;

public class OnboardingAlreadyCompleteException extends RuntimeException {
    public OnboardingAlreadyCompleteException() {
        super("El onboarding ya fue completado — los datos se actualizan desde el perfil");
    }
}
