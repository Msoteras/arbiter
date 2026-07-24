package ar.edu.utn.frba.arbiter.classification.adapters.db;

import ar.edu.utn.frba.arbiter.classification.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory.ClaimRecord;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy.PolicyCoverage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Reads policies and insured history from the shared "BD Aseguradora" — the insurer's
 * system of record, integrated as a separate schema ({@code aseguradora}) inside the same
 * Postgres instance (see arch doc, decision #10 and the {@code db/datos-aseguradoras.sql}
 * seed). Queries qualify the schema explicitly, so no {@code search_path} tweak is needed.
 *
 * <p>Active only under the {@code insurer-db} profile and marked {@code @Primary}, so it
 * shadows {@link ar.edu.utn.frba.arbiter.classification.adapters.mock.MockInsurerAdapter}
 * when enabled and leaves it untouched otherwise (tests keep the in-memory mock).
 *
 * <p>Mapping notes (the DTO is flatter than the schema):
 * <ul>
 *   <li>policy-level {@code insuredAmount}/{@code deductible} come from the primary
 *       coverage (lowest {@code orden}); the deductible is derived from
 *       {@code franquicia_pct} as an absolute amount.</li>
 *   <li>{@code upToDate} maps from {@code estado_pago = 'AL_DIA'} with no outstanding debt.</li>
 *   <li>{@code applicableClauses} is empty: the seed has no clause catalogue yet.</li>
 * </ul>
 */
@Component
@Primary
@Profile("insurer-db")
@RequiredArgsConstructor
public class InsurerDatabaseAdapter implements InsurerAdapter {

    private final JdbcTemplate jdbc;

    @Override
    public InsuredPolicy getPolicy(String policyNumber) {
        PolicyRow row = jdbc.query(
                        """
                        SELECT p.id, p.numero, p.rama, p.producto,
                               p.vigencia_desde, p.vigencia_hasta,
                               p.estado_pago, p.saldo_deuda,
                               a.documento, a.nombre, a.apellido
                        FROM aseguradora.poliza p
                        JOIN aseguradora.asegurado a ON a.id = p.titular_id
                        WHERE p.numero = ?
                        ORDER BY p.id
                        """,
                        (rs, i) -> new PolicyRow(
                                rs.getLong("id"),
                                rs.getString("numero"),
                                rs.getString("rama"),
                                rs.getString("producto"),
                                rs.getObject("vigencia_desde", LocalDate.class),
                                rs.getObject("vigencia_hasta", LocalDate.class),
                                rs.getString("estado_pago"),
                                rs.getBigDecimal("saldo_deuda"),
                                rs.getString("documento"),
                                rs.getString("nombre"),
                                rs.getString("apellido")),
                        policyNumber)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Policy not found: " + policyNumber));

        List<PolicyCoverage> coverages = jdbc.query(
                """
                SELECT orden, nombre, suma_asegurada, franquicia_pct
                FROM aseguradora.cobertura
                WHERE poliza_id = ?
                ORDER BY orden
                """,
                (rs, i) -> {
                    BigDecimal sum = rs.getBigDecimal("suma_asegurada");
                    BigDecimal deductiblePct = rs.getBigDecimal("franquicia_pct");
                    return PolicyCoverage.builder()
                            .code("COB-" + rs.getInt("orden"))
                            .description(rs.getString("nombre"))
                            .insuredAmount(sum)
                            .deductible(absoluteDeductible(sum, deductiblePct))
                            .build();
                },
                row.id());

        PolicyCoverage primary = coverages.isEmpty() ? null : coverages.get(0);

        return InsuredPolicy.builder()
                .policyNumber(row.numero())
                .insuredName((row.nombre() + " " + row.apellido()).trim())
                .insuredId(row.documento())
                .branch(row.rama())
                .product(row.producto())
                .effectiveFrom(row.vigenciaDesde())
                .effectiveTo(row.vigenciaHasta())
                .upToDate(isUpToDate(row.estadoPago(), row.saldoDeuda()))
                .insuredAmount(primary != null ? primary.insuredAmount() : null)
                .deductible(primary != null ? primary.deductible() : null)
                .coverages(coverages)
                .applicableClauses(List.of())
                .build();
    }

    @Override
    public InsuredHistory getHistory(String insuredId) {
        List<ClaimRecord> claims = jdbc.query(
                """
                SELECT h.id, h.fecha_ocurrencia, h.causa, h.estado_resolucion,
                       h.monto_indemnizado, p.rama
                FROM aseguradora.siniestro_historico h
                JOIN aseguradora.asegurado a ON a.id = h.asegurado_id
                JOIN aseguradora.poliza     p ON p.id = h.poliza_id
                WHERE a.documento = ?
                ORDER BY h.fecha_ocurrencia
                """,
                (rs, i) -> ClaimRecord.builder()
                        .claimId(String.valueOf(rs.getLong("id")))
                        .date(rs.getObject("fecha_ocurrencia", LocalDate.class))
                        .branch(rs.getString("rama"))
                        .claimCause(rs.getString("causa"))
                        .status(rs.getString("estado_resolucion"))
                        .amountSettled(rs.getBigDecimal("monto_indemnizado"))
                        .build(),
                insuredId);

        BigDecimal totalSettled = claims.stream()
                .map(ClaimRecord::amountSettled)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDate customerSince = jdbc.query(
                        """
                        SELECT MIN(p.vigencia_desde) AS since
                        FROM aseguradora.poliza     p
                        JOIN aseguradora.asegurado  a ON a.id = p.titular_id
                        WHERE a.documento = ?
                        """,
                        (rs, i) -> rs.getObject("since", LocalDate.class),
                        insuredId)
                .stream()
                .findFirst()
                .orElse(null);

        return InsuredHistory.builder()
                .insuredId(insuredId)
                .previousClaimsCount(claims.size())
                .totalAmountClaimed(totalSettled)
                .customerSince(customerSince != null ? customerSince : LocalDate.now())
                .claims(claims)
                .build();
    }

    private static boolean isUpToDate(String estadoPago, BigDecimal saldoDeuda) {
        boolean noDebt = saldoDeuda == null || saldoDeuda.signum() == 0;
        return "AL_DIA".equalsIgnoreCase(estadoPago) && noDebt;
    }

    /** Turns the coverage's percentage franchise into an absolute amount over its insured sum. */
    private static BigDecimal absoluteDeductible(BigDecimal insuredSum, BigDecimal franchisePct) {
        if (insuredSum == null || franchisePct == null) {
            return null;
        }
        return insuredSum.multiply(franchisePct)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private record PolicyRow(
            long id,
            String numero,
            String rama,
            String producto,
            LocalDate vigenciaDesde,
            LocalDate vigenciaHasta,
            String estadoPago,
            BigDecimal saldoDeuda,
            String documento,
            String nombre,
            String apellido
    ) {}
}
