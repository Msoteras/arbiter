package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.dto.InsuredDirectoryEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Reads {@code aseguradora_<tenant>.asegurado} directly. Overrides the mock under the {@code insurer-db} profile. */
@Component
@Primary
@Profile("insurer-db")
@RequiredArgsConstructor
public class InsuredDirectoryDatabaseAdapter implements InsuredDirectoryAdapter {

    private final JdbcTemplate jdbc;

    /**
     * {@code DISTINCT} on the person: three policies in force is one invitation. The schema is
     * concatenated because it can't be a JDBC parameter; {@code InsurerDbSchema} already validated it.
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
