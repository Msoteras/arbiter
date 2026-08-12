package ar.edu.utn.frba.arbiter.classification.adapters.db;

import ar.edu.utn.frba.arbiter.classification.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.classification.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory.ClaimRecord;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy.PolicyCoverage;
import ar.edu.utn.frba.arbiter.common.tenant.InsurerDbSchema;
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
 * Reads policies and insured history from the "BD Aseguradora" — the insurer's system of record,
 * integrated as separate schemas inside the same Postgres instance (see arch doc, decision #10).
 * One schema per insurer ({@code aseguradora_bbva}, {@code aseguradora_provincia}), resolved from
 * the request's tenant via {@link InsurerDbSchema}: the {@code search_path} only covers Arbiter's
 * own schemas, so every query qualifies this one explicitly.
 *
 * <p>Scoped to the current tenant on purpose, history included: an insurer's database holds its
 * own claims, and showing it another company's would be a leak, not a richer history. The
 * cross-insurer view is Arbiter's (see cases-service's portal), not the insurer's.
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

    /**
     * The insurer database of the tenant this request (or async classification) runs for. Read per
     * call rather than injected: the bean is a singleton and the tenant changes per request.
     */
    private static String schema() {
        return InsurerDbSchema.forTenant(TenantContext.get());
    }

    @Override
    public InsuredPolicy getPolicy(String policyNumber) {
        String schema = schema();
        PolicyRow row = jdbc.query(
                        """
                        SELECT p.id, p.numero, p.rama, p.producto, p.bien_asegurado, p.imei,
                               p.vigencia_desde, p.vigencia_hasta,
                               p.estado_pago, p.saldo_deuda,
                               a.documento, a.nombre, a.apellido
                        FROM %1$s.poliza p
                        JOIN %1$s.asegurado a ON a.id = p.titular_id
                        WHERE p.numero = ?
                        ORDER BY p.id
                        """.formatted(schema),
                        (rs, i) -> new PolicyRow(
                                rs.getLong("id"),
                                rs.getString("numero"),
                                rs.getString("rama"),
                                rs.getString("producto"),
                                rs.getString("bien_asegurado"),
                                rs.getString("imei"),
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
                FROM %s.cobertura
                WHERE poliza_id = ?
                ORDER BY orden
                """.formatted(schema),
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
                .insuredItem(row.bienAsegurado())
                .imei(row.imei())
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
        String schema = schema();
        List<ClaimRecord> claims = jdbc.query(
                """
                SELECT h.id, h.fecha_ocurrencia, h.causa, h.estado_resolucion,
                       h.monto_indemnizado, p.rama, p.numero
                FROM %1$s.siniestro_historico h
                JOIN %1$s.asegurado a ON a.id = h.asegurado_id
                JOIN %1$s.poliza     p ON p.id = h.poliza_id
                WHERE a.documento = ?
                ORDER BY h.fecha_ocurrencia
                """.formatted(schema),
                (rs, i) -> ClaimRecord.builder()
                        .claimId(String.valueOf(rs.getLong("id")))
                        .date(rs.getObject("fecha_ocurrencia", LocalDate.class))
                        .policyNumber(rs.getString("numero"))
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
                        FROM %1$s.poliza     p
                        JOIN %1$s.asegurado  a ON a.id = p.titular_id
                        WHERE a.documento = ?
                        """.formatted(schema),
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
            String bienAsegurado,
            String imei,
            LocalDate vigenciaDesde,
            LocalDate vigenciaHasta,
            String estadoPago,
            BigDecimal saldoDeuda,
            String documento,
            String nombre,
            String apellido
    ) {}
}
