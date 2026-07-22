package ar.edu.utn.frba.arbiter.cases.adapters.db;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse.Coverage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Lee las pólizas del asegurado desde la BD Aseguradora (schema {@code aseguradora} en la
 * misma instancia Postgres, cargado por {@code db/datos-aseguradoras.sql} — ver arch doc,
 * decisión #10). Mismo enfoque que el {@code InsurerDatabaseAdapter} de classification-service,
 * pero orientado al portal: agrega la aseguradora (JOIN a {@code compania}) y lista TODAS las
 * pólizas de un asegurado cruzando compañías ({@code documento} puede repetirse por tenant).
 *
 * <p>Activo solo bajo el perfil {@code insurer-db} y {@code @Primary}: gana sobre
 * {@link ar.edu.utn.frba.arbiter.cases.adapters.mock.MockInsurerAdapter} cuando está prendido,
 * y lo deja intacto (default para tests / dev sin BD aseguradora).
 */
@Component
@Primary
@Profile("insurer-db")
@RequiredArgsConstructor
public class InsurerDatabaseAdapter implements InsurerAdapter {

    private static final String POLICY_SELECT = """
            SELECT p.id, p.numero, p.rama, p.producto, p.bien_asegurado,
                   p.vigencia_desde, p.vigencia_hasta,
                   p.estado_pago, p.saldo_deuda,
                   a.documento, a.nombre, a.apellido, a.email, a.telefono,
                   c.id     AS compania_id,
                   c.nombre AS compania_nombre
            FROM aseguradora.poliza p
            JOIN aseguradora.asegurado a ON a.id = p.titular_id
            JOIN aseguradora.compania  c ON c.id = p.aseguradora_id
            """;

    private final JdbcTemplate jdbc;

    @Override
    public Optional<PolicyResponse> findPolicy(String policyNumber) {
        return jdbc.query(POLICY_SELECT + " WHERE p.numero = ? ORDER BY p.id", this::mapRow, policyNumber)
                .stream()
                .findFirst()
                .map(this::toResponse);
    }

    @Override
    public List<PolicyResponse> findPoliciesByInsured(String insuredId) {
        // El mismo documento puede tener pólizas en varias compañías → vista centralizada.
        return jdbc.query(
                        POLICY_SELECT + " WHERE a.documento = ? ORDER BY c.nombre, p.numero",
                        this::mapRow,
                        insuredId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /** Segunda pasada: la suma asegurada vive en cada cobertura; la de la póliza es la primaria. */
    private PolicyResponse toResponse(PolicyRow row) {
        List<Coverage> coverages = jdbc.query(
                """
                SELECT orden, nombre, suma_asegurada, franquicia_pct
                FROM aseguradora.cobertura
                WHERE poliza_id = ?
                ORDER BY orden
                """,
                (rs, i) -> {
                    BigDecimal sum = rs.getBigDecimal("suma_asegurada");
                    return Coverage.builder()
                            .code("COB-" + rs.getInt("orden"))
                            .description(rs.getString("nombre"))
                            .insuredAmount(sum)
                            .deductible(absoluteDeductible(sum, rs.getBigDecimal("franquicia_pct")))
                            .build();
                },
                row.id());

        Coverage primary = coverages.isEmpty() ? null : coverages.get(0);
        return PolicyResponse.builder()
                .policyNumber(row.numero())
                .insurerId(String.valueOf(row.companiaId()))
                .insurerName(row.companiaNombre())
                .insuredName((row.nombre() + " " + row.apellido()).trim())
                .insuredId(row.documento())
                .contactEmail(row.email())
                .contactPhone(row.telefono())
                .branch(row.rama())
                .insuredItem(row.bienAsegurado())
                .product(row.producto())
                .effectiveFrom(row.vigenciaDesde())
                .effectiveTo(row.vigenciaHasta())
                .upToDate(isUpToDate(row.estadoPago(), row.saldoDeuda()))
                .insuredAmount(primary != null ? primary.insuredAmount() : null)
                .deductible(primary != null ? primary.deductible() : null)
                .coverages(coverages)
                .build();
    }

    private PolicyRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PolicyRow(
                rs.getLong("id"),
                rs.getString("numero"),
                rs.getString("rama"),
                rs.getString("producto"),
                rs.getString("bien_asegurado"),
                rs.getObject("vigencia_desde", LocalDate.class),
                rs.getObject("vigencia_hasta", LocalDate.class),
                rs.getString("estado_pago"),
                rs.getBigDecimal("saldo_deuda"),
                rs.getString("documento"),
                rs.getString("nombre"),
                rs.getString("apellido"),
                rs.getString("email"),
                rs.getString("telefono"),
                rs.getLong("compania_id"),
                rs.getString("compania_nombre"));
    }

    private static boolean isUpToDate(String estadoPago, BigDecimal saldoDeuda) {
        boolean noDebt = saldoDeuda == null || saldoDeuda.signum() == 0;
        return "AL_DIA".equalsIgnoreCase(estadoPago) && noDebt;
    }

    /** Franquicia porcentual → monto absoluto sobre la suma asegurada de la cobertura. */
    private static BigDecimal absoluteDeductible(BigDecimal insuredSum, BigDecimal franchisePct) {
        if (insuredSum == null || franchisePct == null) {
            return null;
        }
        return insuredSum.multiply(franchisePct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private record PolicyRow(
            long id,
            String numero,
            String rama,
            String producto,
            String bienAsegurado,
            LocalDate vigenciaDesde,
            LocalDate vigenciaHasta,
            String estadoPago,
            BigDecimal saldoDeuda,
            String documento,
            String nombre,
            String apellido,
            String email,
            String telefono,
            long companiaId,
            String companiaNombre
    ) {}
}
