package ar.edu.utn.frba.arbiter.rules.exceptions;

/** The rule's JSONB configuration could not be serialized/parsed into a valid config. */
public class InvalidRuleConfigurationException extends RuntimeException {

    public InvalidRuleConfigurationException(String message) {
        super(message);
    }
}
