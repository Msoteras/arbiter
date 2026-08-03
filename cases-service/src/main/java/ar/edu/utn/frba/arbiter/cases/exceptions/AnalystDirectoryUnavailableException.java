package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * auth-service didn't answer the analyst lookup. Surfaces as 503 instead of a generic 500: the
 * assignment is retryable once the other module is back, nothing was persisted.
 */
public class AnalystDirectoryUnavailableException extends RuntimeException {

    public AnalystDirectoryUnavailableException(Throwable cause) {
        super("No se pudo consultar el listado de analistas en auth-service", cause);
    }
}
