package ar.edu.utn.frba.arbiter.common.tenant;

import org.springframework.jdbc.core.JdbcOperations;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Translates the line name the insurer database stores ({@code poliza.rama}) into the name the
 * insurer gave that branch in Arbiter. The match is on {@code branch.external_name}, which a rename
 * never touches, so everything downstream can keep resolving the branch by its current name.
 *
 * <p>Shared for the same reason as {@link InsurerDbSchema}: cases-service and classification-service
 * both read that database, and a claim filed under one name must be classified under the same one.
 * Read with an explicit schema and plain JDBC because neither caller can rely on the
 * {@code search_path}: one walks several insurers in a single request, the other borrows pooled
 * connections that sit on the common schema.
 */
public final class InsurerBranchNames {

    /** The schema name is concatenated into SQL, so it is validated before use. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");

    private final Map<String, String> nameByExternalName;

    private InsurerBranchNames(Map<String, String> nameByExternalName) {
        this.nameByExternalName = nameByExternalName;
    }

    /** One query per insurer, so a listing of many policies doesn't look the branch up row by row. */
    public static InsurerBranchNames load(JdbcOperations jdbc, String tenantSchema) {
        if (tenantSchema == null || !SAFE_IDENTIFIER.matcher(tenantSchema).matches()) {
            throw new IllegalStateException("Unsafe tenant schema name: " + tenantSchema);
        }
        Map<String, String> names = new HashMap<>();
        jdbc.query(
                "SELECT external_name, name FROM %s.branch WHERE external_name IS NOT NULL"
                        .formatted(tenantSchema),
                rs -> {
                    names.put(rs.getString("external_name"), rs.getString("name"));
                });
        return new InsurerBranchNames(names);
    }

    /**
     * A line the insurer never catalogued comes back as it arrived: it then matches no branch
     * downstream, which is the same outcome as before and not a reason to fail the read.
     */
    public String nameOf(String externalName) {
        return nameByExternalName.getOrDefault(externalName, externalName);
    }
}
