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
import java.time.LocalDateTime;
import java.util.List;

/**
 * Reads policies and insured history from the insurer's database, one schema per insurer resolved
 * via {@link InsurerDbSchema}. The {@code search_path} only covers Arbiter's schemas, so every query
 * qualifies the insurer schema explicitly. History is tenant-scoped on purpose: another insurer's
 * claims would be a leak. Enabled by the {@code insurer-db} profile; otherwise the mock is used.
 *
 * <p>Policy-level {@code insuredAmount}/{@code deductible} come from the primary coverage (lowest
 * {@code orden}), with the deductible derived from {@code franquicia_pct}.
 */
@Component
@Primary
@Profile("insurer-db")
@RequiredArgsConstructor
public class InsurerDatabaseAdapter implements InsurerAdapter {

    private final JdbcTemplate jdbc;

    /** Resolved per call: the bean is a singleton and the tenant changes per request. */
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
                               p.estado_pago, p.saldo_deuda, p.importe_cuota,
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
                                rs.getObject("vigencia_desde", LocalDateTime.class),
                                rs.getObject("vigencia_hasta", LocalDateTime.class),
                                rs.getString("estado_pago"),
                                rs.getBigDecimal("saldo_deuda"),
                                rs.getBigDecimal("importe_cuota"),
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
                .installmentAmount(row.importeCuota())
                .overdueBalance(row.saldoDeuda())
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
                       h.monto_indemnizado, p.rama, p.numero, c.nombre AS cobertura
                FROM %1$s.siniestro_historico h
                JOIN %1$s.asegurado a ON a.id = h.asegurado_id
                JOIN %1$s.poliza     p ON p.id = h.poliza_id
                LEFT JOIN %1$s.cobertura c ON c.id = h.cobertura_id
                WHERE a.documento = ?
                ORDER BY h.fecha_ocurrencia
                """.formatted(schema),
                (rs, i) -> ClaimRecord.builder()
                        .claimId(String.valueOf(rs.getLong("id")))
                        .date(rs.getObject("fecha_ocurrencia", LocalDate.class))
                        .policyNumber(rs.getString("numero"))
                        .branch(rs.getString("rama"))
                        // Null when no coverage was recorded: the exhaustion rule skips it rather than guessing.
                        .coverageName(rs.getString("cobertura"))
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
                        SELECT MIN(p.vigencia_desde)::date AS since
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
            LocalDateTime vigenciaDesde,
            LocalDateTime vigenciaHasta,
            String estadoPago,
            BigDecimal saldoDeuda,
            BigDecimal importeCuota,
            String documento,
            String nombre,
            String apellido
    ) {}
}
