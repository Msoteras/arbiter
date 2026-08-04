package ar.edu.utn.frba.arbiter.auth.config.tenant;

/**
 * Per-request holder for the resolved tenant schema. Two writers: the login flow (which
 * has no JWT yet to read a tenant from — it resolves one itself) and
 * {@link TenantResolvingFilter} (for every other authenticated request). Both clear it
 * in a finally block; an uncleared value would leak into whatever thread handles the
 * next request, since Tomcat reuses worker threads.
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
