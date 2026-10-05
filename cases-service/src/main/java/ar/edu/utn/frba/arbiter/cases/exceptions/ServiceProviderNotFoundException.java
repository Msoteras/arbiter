package ar.edu.utn.frba.arbiter.cases.exceptions;

public class ServiceProviderNotFoundException extends RuntimeException {

    public ServiceProviderNotFoundException(Long providerId) {
        super("No service provider available for this case with id " + providerId);
    }
}
