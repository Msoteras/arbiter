package ar.edu.utn.frba.arbiter.classification.config.tenant;

/**
 * Per-request holder for the resolved tenant schema. Set by {@link TenantResolvingFilter}
 * from the JWT's {@code tenantSchema} claim and cleared when the request ends.
 *
 * <p>Unlike rules-service and reports-service — where the equivalent class is scaffolding
 * with nothing populating it yet — every authenticated request to this module goes through
 * the filter, so the value here is real.
 *
 * <p>The fallback matters: a request that arrives without a resolved tenant (an unauthenticated
 * path, or an async task running off the request thread) reads the common schema rather than
 * silently borrowing whichever tenant the pooled connection last served.
 */
public final class TenantContext {

    /** Search path always falls back here — the schema shared by every insurer. */
    public static final String COMMON_SCHEMA = "arbiter_common";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static String get() {
        String schema = CURRENT.get();
        return schema != null ? schema : COMMON_SCHEMA;
    }

    public static void set(String schema) {
        CURRENT.set(schema);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
