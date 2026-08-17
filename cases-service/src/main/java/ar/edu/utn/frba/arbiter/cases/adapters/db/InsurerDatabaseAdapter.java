package ar.edu.utn.frba.arbiter.cases.adapters.db;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.adapters.db.CallerInsurerDatabases.InsurerDatabase;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse.Coverage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lee las pólizas del asegurado desde la BD Aseguradora (ver arch doc, decisión #10): un esquema
 * {@code aseguradora_<tenant>} por compañía dentro de la misma instancia Postgres, creados por
 * {@code db/init-multitenant.sql}. Mismo enfoque que el {@code InsurerDatabaseAdapter} de
 * classification-service, pero orientado al portal: recorre <b>todas</b> las aseguradoras del que
 * llama ({@link CallerInsurerDatabases}), porque un mismo DNI puede tener pólizas en varias.
 *
 * <p>La compañía sale del registro de la plataforma y no de la tabla {@code compania}: con un
 * esquema por aseguradora esa tabla tiene una fila sola y su id es siempre 1 — el discriminador
 * {@code poliza.aseguradora_id} del modelo single-schema ya no existe.
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
                   a.documento, a.nombre, a.apellido, a.email, a.telefono
            FROM %1$s.poliza p
            JOIN %1$s.asegurado a ON a.id = p.titular_id
            """;

    private final JdbcTemplate jdbc;
    private final CallerInsurerDatabases insurerDatabases;

    /** Primera que la tenga: un número de póliza es único dentro de una compañía. */
    @Override
    public Optional<PolicyResponse> findPolicy(String policyNumber) {
        for (InsurerDatabase database : insurerDatabases.forCaller()) {
            Optional<PolicyResponse> found = jdbc.query(
                            POLICY_SELECT.formatted(database.schema()) + " WHERE p.numero = ? ORDER BY p.id",
                            this::mapRow,
                            policyNumber)
                    .stream()
                    .findFirst()
                    .map(row -> toResponse(row, database));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * El mismo documento puede tener pólizas en varias compañías → vista centralizada.
     *
     * <p>Solo pólizas vigentes ahora mismo ({@code vigencia_hasta >= NOW()}, no {@code
     * CURRENT_DATE}: la vigencia lleva hora, así que una póliza que vence hoy a las 08:00 ya no
     * es vigente a las 14:00 aunque siga siendo "hoy"): esto alimenta el desplegable del alta de
     * denuncia, y una póliza vencida ahí solo lleva al asegurado a completar todo el wizard para
     * enterarse recién al final que {@link
     * ar.edu.utn.frba.arbiter.cases.services.PolicyEligibilityValidator} la va a rechazar. El
     * lookup puntual por número ({@link #findPolicy}) no filtra — a ese se llega por otros
     * caminos (expediente ya creado, chequeo de elegibilidad) donde una póliza vencida es un
     * resultado legítimo, no ruido.
     */
    @Override
    public List<PolicyResponse> findPoliciesByInsured(String insuredId) {
        List<PolicyResponse> policies = new ArrayList<>();
        for (InsurerDatabase database : insurerDatabases.forCaller()) {
            jdbc.query(POLICY_SELECT.formatted(database.schema())
                                    + " WHERE a.documento = ? AND p.vigencia_hasta >= NOW()"
                                    + " ORDER BY p.numero",
                            this::mapRow,
                            insuredId)
                    .forEach(row -> policies.add(toResponse(row, database)));
        }
        return List.copyOf(policies);
    }

    /** Segunda pasada: la suma asegurada vive en cada cobertura; la de la póliza es la primaria. */
    private PolicyResponse toResponse(PolicyRow row, InsurerDatabase database) {
        List<Coverage> coverages = jdbc.query(
                """
                SELECT orden, nombre, suma_asegurada, franquicia_pct
                FROM %s.cobertura
                WHERE poliza_id = ?
                ORDER BY orden
                """.formatted(database.schema()),
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
                .insurerId(String.valueOf(database.insurerId()))
                .insurerName(database.insurerName())
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
                rs.getObject("vigencia_desde", LocalDateTime.class),
                rs.getObject("vigencia_hasta", LocalDateTime.class),
                rs.getString("estado_pago"),
                rs.getBigDecimal("saldo_deuda"),
                rs.getString("documento"),
                rs.getString("nombre"),
                rs.getString("apellido"),
                rs.getString("email"),
                rs.getString("telefono"));
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
            LocalDateTime vigenciaDesde,
            LocalDateTime vigenciaHasta,
            String estadoPago,
            BigDecimal saldoDeuda,
            String documento,
            String nombre,
            String apellido,
            String email,
            String telefono
    ) {}
}
