package ar.edu.utn.frba.arbiter.auth.exceptions;

/**
 * The insurer's directory and the platform disagree about who a document belongs to: the
 * {@code insured} row for that DNI is already there, hanging off a <b>different</b> user than the
 * one the directory's email resolves to.
 *
 * <p>It is not a hypothetical. On the shared database today, {@code aseguradora_bbva} lists
 * Martina Soteras's address as one that on the platform belongs to an analyst's account — test data
 * that drifted when addresses were repointed at real inboxes. Provisioning her would have found her
 * profile by document, seen it was not missing, and reported the person as "already had an account"
 * while quietly pairing her with someone else's login.
 *
 * <p>Thrown rather than reported as a value so the per-person transaction rolls back: a run that
 * created the user first and only then noticed the clash would leave an account with no profile
 * behind it, which is the state that breaks login. Skipped and named in the summary is the honest
 * outcome — the mismatch is the company's data to reconcile, not something to guess at here.
 */
public class InsuredProfileConflictException extends RuntimeException {

    public InsuredProfileConflictException(String dni, Long existingUserId, Long incomingUserId) {
        super(("el documento %s ya tiene perfil de asegurado apuntando al usuario %d, pero su email "
                + "en la aseguradora resuelve al usuario %d — hay que reconciliar ese dato antes de darlo de alta")
                .formatted(dni, existingUserId, incomingUserId));
    }
}
