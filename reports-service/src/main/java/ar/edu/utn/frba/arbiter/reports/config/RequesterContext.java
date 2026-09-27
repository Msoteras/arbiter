package ar.edu.utn.frba.arbiter.reports.config;

/**
 * Who asked for the report, for the "Solicitado por" line every exported document carries.
 *
 * <p>Filled by {@code TenantResolvingFilter}, which already parses the JWT to resolve the tenant:
 * the name and the role travel in the same token, and parsing it twice would be two places to keep
 * in step.
 *
 * <p>A report leaves the company and gets filed. Stating who ran it is part of the audit trail the
 * export is for, and it is the one thing on the page that cannot be recomputed later.
 */
public final class RequesterContext {

    /**
     * @param name null when the token carries no name, e.g. a service token from a scheduled run
     * @param role the caller's role literal, as the JWT spells it
     */
    public record Requester(String name, String role) {}

    private static final ThreadLocal<Requester> CURRENT = new ThreadLocal<>();

    private RequesterContext() {
    }

    /** @return null when the request carried no identifiable caller */
    public static Requester get() {
        return CURRENT.get();
    }

    public static void set(Requester requester) {
        CURRENT.set(requester);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
