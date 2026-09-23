package ar.edu.utn.frba.arbiter.cases.config.tenant;

/**
 * Falls back to the common schema when no tenant was resolved (unauthenticated path, async task),
 * rather than silently borrowing whichever tenant the pooled connection last served.
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
