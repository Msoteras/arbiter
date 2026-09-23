package ar.edu.utn.frba.arbiter.classification.config.tenant;

/**
 * Per-request tenant schema, set by {@link TenantResolvingFilter} from the JWT. Without one it falls
 * back to the common schema rather than whichever tenant the pooled connection last served.
 */
public final class TenantContext {

    public static final String COMMON_SCHEMA = "arbiter_common";

    /** Identifiers can't be bind parameters, so anything interpolated into SQL is validated first. */
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
     * For raw-JDBC repositories to qualify their tables: {@code TenantConnectionProvider} only covers
     * Hibernate's connections, so a {@code JdbcTemplate} connection has the common search_path.
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
