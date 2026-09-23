package ar.edu.utn.frba.arbiter.common.tenant;

import java.util.regex.Pattern;

/**
 * The insurer database schema that belongs to a tenant: {@code arbiter_bbva} →
 * {@code aseguradora_bbva}. Shared because cases-service and classification-service both read that
 * database and must agree on the name. Derived rather than stored so it can't disagree with
 * {@code insurer.schema_name}.
 */
public final class InsurerDbSchema {

    private static final String TENANT_PREFIX = "arbiter_";
    private static final String INSURER_PREFIX = "aseguradora_";

    private static final String COMMON_SCHEMA = "arbiter_common";

    /** The schema name is concatenated into SQL, so it never leaves this class unvalidated. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");

    private InsurerDbSchema() {
    }

    /**
     * @throws IllegalStateException if the tenant is unresolved ({@code arbiter_common} has no
     *         insurer database) or the name is not a plain lowercase identifier
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
