package ar.edu.utn.frba.arbiter.reports.config.tenant;

/**
 * Per-request holder for the resolved tenant schema — same pattern as auth-service's
 * {@code TenantContext}. Set by {@link TenantResolvingFilter} from the JWT's {@code tenantSchema}
 * claim; every report reads the tables of the schema resolved here.
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

    /**
     * Whether this request carries an insurer. {@link #get()} can't tell: it falls back to the
     * common schema, which is the right place to borrow a connection from but has no cases at all.
     */
    public static boolean isResolved() {
        return CURRENT.get() != null;
    }

    public static void set(String schema) {
        CURRENT.set(schema);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
