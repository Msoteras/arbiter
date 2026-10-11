package ar.edu.utn.frba.arbiter.common.tenant;

import org.springframework.jdbc.core.JdbcOperations;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Translates the coverage name the insurer database stores ({@code cobertura.nombre}) into the name
 * the insurer gave that coverage in Arbiter. The match is on {@code coverage.external_name}, which a
 * rename never touches, so everything downstream can keep resolving the coverage by its current name.
 *
 * <p>Keyed by line as well: the insurer database reuses one coverage name across lines ("Robo de
 * celular" also covers a laptop), and each line's coverage has its own name and rules here. Both
 * halves of the key are the insurer database's own names, since that is all a policy row carries.
 *
 * <p>Shared and read with plain JDBC for the same reasons as {@link InsurerBranchNames}.
 */
public final class InsurerCoverageNames {

    /** The schema name is concatenated into SQL, so it is validated before use. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");

    private final Map<Key, String> nameByExternalName;

    private InsurerCoverageNames(Map<Key, String> nameByExternalName) {
        this.nameByExternalName = nameByExternalName;
    }

    /** One query per insurer, so a listing of many policies doesn't look coverages up row by row. */
    public static InsurerCoverageNames load(JdbcOperations jdbc, String tenantSchema) {
        if (tenantSchema == null || !SAFE_IDENTIFIER.matcher(tenantSchema).matches()) {
            throw new IllegalStateException("Unsafe tenant schema name: " + tenantSchema);
        }
        Map<Key, String> names = new HashMap<>();
        jdbc.query(
                ("SELECT b.external_name AS branch_external_name, c.external_name, c.name "
                        + "FROM %1$s.coverage c JOIN %1$s.branch b ON b.id = c.branch_id "
                        + "WHERE c.external_name IS NOT NULL AND b.external_name IS NOT NULL")
                        .formatted(tenantSchema),
                rs -> {
                    names.put(new Key(rs.getString("branch_external_name"), rs.getString("external_name")),
                            rs.getString("name"));
                });
        return new InsurerCoverageNames(names);
    }

    /**
     * A coverage the insurer never catalogued comes back as it arrived: it then matches no coverage
     * downstream, which is what an unconfigured coverage already did.
     *
     * @param branchExternalName the line as the insurer database names it ({@code poliza.rama})
     */
    public String nameOf(String branchExternalName, String externalName) {
        return nameByExternalName.getOrDefault(new Key(branchExternalName, externalName), externalName);
    }

    private record Key(String branchExternalName, String externalName) {}
}
