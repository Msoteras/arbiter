package ar.edu.utn.frba.arbiter.common.email;

public class EmailDeliveryException extends RuntimeException {
    public EmailDeliveryException(String to, Throwable cause) {
        super("No se pudo mandar el mail a " + to, cause);
    }

    public EmailDeliveryException(String to, int statusCode, String responseBody) {
        super("No se pudo mandar el mail a " + to + " (SendGrid respondió " + statusCode + ": " + responseBody + ")");
    }
}
