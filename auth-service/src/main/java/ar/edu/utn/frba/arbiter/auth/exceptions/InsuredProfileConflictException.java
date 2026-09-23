package ar.edu.utn.frba.arbiter.auth.exceptions;

/**
 * The {@code insured} row for a DNI already belongs to a different user than the one the
 * directory's email resolves to. Thrown (not returned) so the per-person transaction rolls back
 * instead of leaving an account without a profile, which breaks login.
 */
public class InsuredProfileConflictException extends RuntimeException {

    public InsuredProfileConflictException(String dni, Long existingUserId, Long incomingUserId) {
        super(("el documento %s ya tiene perfil de asegurado apuntando al usuario %d, pero su email "
                + "en la aseguradora resuelve al usuario %d — hay que reconciliar ese dato antes de darlo de alta")
                .formatted(dni, existingUserId, incomingUserId));
    }
}
