package ar.edu.utn.frba.arbiter.rules.config.tenant;

/**
 * Per-request holder for the resolved tenant schema — same pattern as auth-service's
 * {@code TenantContext}. Set by {@link TenantResolvingFilter}, which is wired even though
 * this module still has no controllers (catalog-only scaffolding): it's cheap to have ready
 * for whenever the first real endpoint lands, and matches the other 4 modules.
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
