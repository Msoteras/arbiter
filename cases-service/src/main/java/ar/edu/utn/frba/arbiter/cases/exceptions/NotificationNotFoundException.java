package ar.edu.utn.frba.arbiter.cases.exceptions;

/** The notification doesn't exist, or belongs to someone else — the caller can't tell which. */
public class NotificationNotFoundException extends RuntimeException {
    public NotificationNotFoundException() {
        super("No encontramos esa notificación.");
    }
}
