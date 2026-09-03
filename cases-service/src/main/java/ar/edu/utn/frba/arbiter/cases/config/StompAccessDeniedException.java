package ar.edu.utn.frba.arbiter.cases.config;

import org.springframework.messaging.MessagingException;

/** Extends MessagingException so Spring aborts the frame and answers ERROR instead of dropping it. */
public class StompAccessDeniedException extends MessagingException {

    public StompAccessDeniedException(String message) {
        super(message);
    }
}
