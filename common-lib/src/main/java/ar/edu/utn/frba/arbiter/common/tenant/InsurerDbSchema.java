package ar.edu.utn.frba.arbiter.common.tenant;

import java.util.regex.Pattern;

/**
 * The "BD Aseguradora" schema that belongs to a tenant: {@code arbiter_bbva} → {@code
 * aseguradora_bbva}, the pairing {@code db/init-multitenant.sql} provisions
 * ({@code create_tenant_schema} / {@code create_insurer_db_schema}).
 *
 * <p>Lives in common-lib because two modules read that database (cases-service for the portal,
 * classification-service for policy and history) and both have to agree on the name. Deriving it
 * twice is how they drift apart, which is the shape the bug already took: both adapters kept
 * querying the bare {@code aseguradora} schema of the single-schema model long after the seed
 * stopped creating it.
 *
 * <p>Derived rather than stored: {@code insurer.schema_name} is already UNIQUE, so a second column
 * would only add a way for the two to disagree — same reasoning as the insurer slug in the URL.
 */
public final class InsurerDbSchema {

    private static final String TENANT_PREFIX = "arbiter_";
    private static final String INSURER_PREFIX = "aseguradora_";

    /** The platform's shared schema — a tenant fallback, not an insurer. */
    private static final String COMMON_SCHEMA = "arbiter_common";

    /** The schema name is concatenated into SQL, so it never leaves this class unvalidated. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");

    private InsurerDbSchema() {
    }

    /**
     * @param tenantSchema an Arbiter tenant schema ({@code arbiter_bbva})
     * @return the insurer's own schema ({@code aseguradora_bbva})
     * @throws IllegalStateException if the tenant is unresolved ({@code arbiter_common} has no
     *         insurer database behind it) or the name is not a plain lowercase identifier
     */
    public static String forTenant(String tenantSchema) {
        if (tenantSchema == null || !tenantSchema.startsWith(TENANT_PREFIX)
                || COMMON_SCHEMA.equals(tenantSchema)) {
            throw new IllegalStateException(
                    "No insurer database for tenant schema: " + tenantSchema
                            + " — the caller's tenant has to be resolved before reading it");
        }
        String schema = INSURER_PREFIX + tenantSchema.substring(TENANT_PREFIX.length());
        if (!SAFE_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalStateException("Unsafe insurer database schema name: " + schema);
        }
        return schema;
    }
}
