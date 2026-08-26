package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.dto.InsuredDirectoryEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reads {@code aseguradora_<tenant>.asegurado} straight from the insurer's database — the
 * integration decision #10 calls "por base de datos compartida", the same way
 * cases-service and classification-service reach it.
 *
 * <p>Active only under the {@code insurer-db} profile and {@code @Primary} over
 * {@link MockInsuredDirectoryAdapter}, so dev and tests without an insurer database keep working.
 */
@Component
@Primary
@Profile("insurer-db")
@RequiredArgsConstructor
public class InsuredDirectoryDatabaseAdapter implements InsuredDirectoryAdapter {

    private final JdbcTemplate jdbc;

    /**
     * {@code DISTINCT} on the person, not the policy: someone with three policies in force is one
     * account, not three invitations. The schema name is concatenated rather than bound because a
     * schema cannot be a JDBC parameter — it comes from
     * {@link ar.edu.utn.frba.arbiter.common.tenant.InsurerDbSchema}, which validates the identifier
     * before it ever reaches here, and never from the request.
     */
    @Override
    public List<InsuredDirectoryEntry> findWithPoliciesInForce(String insurerDbSchema) {
        return jdbc.query("""
                SELECT DISTINCT a.documento, a.nombre, a.apellido, a.email, a.telefono
                FROM %1$s.asegurado a
                JOIN %1$s.poliza p ON p.titular_id = a.id
                WHERE p.vigencia_hasta >= NOW()
                ORDER BY a.apellido, a.nombre
                """.formatted(insurerDbSchema),
                (rs, i) -> new InsuredDirectoryEntry(
                        rs.getString("documento"),
                        rs.getString("nombre"),
                        rs.getString("apellido"),
                        rs.getString("email"),
                        rs.getString("telefono")));
    }
}
