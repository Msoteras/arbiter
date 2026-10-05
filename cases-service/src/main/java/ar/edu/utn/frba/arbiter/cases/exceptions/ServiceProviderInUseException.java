package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * A provider that already received derivations is deactivated, not deleted: deleting it would leave
 * referrals pointing to a missing row.
 */
public class ServiceProviderInUseException extends RuntimeException {

    public ServiceProviderInUseException(Long providerId) {
        super("El proveedor " + providerId + " ya tiene derivaciones registradas: desactivalo en vez de borrarlo");
    }
}
