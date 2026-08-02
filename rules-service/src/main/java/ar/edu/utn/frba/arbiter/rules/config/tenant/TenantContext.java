package ar.edu.utn.frba.arbiter.rules.config.tenant;

/**
 * Per-request holder for the resolved tenant schema — same pattern as auth-service's
 * {@code TenantContext}. Nothing sets this yet: rules-service has no controllers or
 * Spring Security wired up (still catalog-only scaffolding), so there's no request to
 * resolve a tenant from. This exists so the connection provider and identifier resolver
 * below have a real ThreadLocal to read once that JWT-reading filter gets built
 * alongside this module's first real endpoint.
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
