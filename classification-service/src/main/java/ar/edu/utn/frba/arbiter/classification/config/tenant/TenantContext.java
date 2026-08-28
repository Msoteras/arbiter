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

    /**
     * Same guard as {@code TenantConnectionProvider}: schema names come from
     * {@code insurer.schema_name} (server-controlled), never from request input, but an identifier
     * can't be a bind parameter — so anything interpolated into SQL gets validated first.
     */
    private static final java.util.regex.Pattern SAFE_SCHEMA =
            java.util.regex.Pattern.compile("^[a-z_][a-z0-9_]*$");

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static String get() {
        String schema = CURRENT.get();
        return schema != null ? schema : COMMON_SCHEMA;
    }

    /**
     * The current schema, validated for safe interpolation into a SQL identifier position.
     *
     * <p>Needed because {@code TenantConnectionProvider} only intercepts the connections
     * <b>Hibernate</b> borrows: a plain {@code JdbcTemplate} gets a raw pooled connection whose
     * {@code search_path} was reset on release, so an unqualified {@code FROM cases} resolves
     * against {@code arbiter_common} — where the per-tenant tables don't exist — and fails with
     * {@code relation "cases" does not exist}. Raw-JDBC repositories in this module qualify their
     * tables with this.
     */
    public static String schemaForSql() {
        String schema = get();
        if (!SAFE_SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Unsafe tenant schema identifier: " + schema);
        }
        return schema;
    }

    public static void set(String schema) {
        CURRENT.set(schema);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
