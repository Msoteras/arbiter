package ar.edu.utn.frba.arbiter.auth.config.tenant;

/**
 * Per-request holder for the resolved tenant schema, set by the login flow (no JWT yet) and by
 * {@link TenantResolvingFilter}. Always clear it in a finally block: Tomcat reuses worker threads.
 */
public final class TenantContext {

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
