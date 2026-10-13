package ar.edu.utn.frba.arbiter.rules.exceptions;

import ar.edu.utn.frba.arbiter.common.enums.OnboardingStep;

/** A required step can't be marked as not used. */
public class OnboardingStepNotSkippableException extends RuntimeException {

    public OnboardingStepNotSkippableException(OnboardingStep step) {
        super("Este paso es obligatorio: no se puede marcar como «No se usa».");
    }
}
