package ar.edu.utn.frba.arbiter.reports.exceptions;

/**
 * The caller's token carries no insurer. Every report is about one insurer's cases, so there is
 * nothing to answer with — and an empty report would read as "nothing was resolved".
 */
public class TenantNotResolvedException extends RuntimeException {

    public TenantNotResolvedException() {
        super("The session has no insurer resolved; reports are always scoped to one insurer");
    }
}
