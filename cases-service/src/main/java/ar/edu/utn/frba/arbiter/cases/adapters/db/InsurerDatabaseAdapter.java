package ar.edu.utn.frba.arbiter.cases.adapters.db;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.adapters.db.CallerInsurerDatabases.InsurerDatabase;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse.Coverage;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse.Validity;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Reads policies from the insurer DB, walking every insurer the caller belongs to
 * ({@link CallerInsurerDatabases}), since one DNI may hold policies at several. The insurer comes from
 * the platform registry, not the {@code compania} table, whose id is always 1 with one schema per insurer.
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

    /** First match wins: a policy number is unique within an insurer. */
    @Override
    public Optional<PolicyResponse> findPolicy(String policyNumber) {
        for (InsurerDatabase database : insurerDatabases.forCaller()) {
            Optional<PolicyResponse> found = jdbc.query(
                            POLICY_SELECT.formatted(database.schema()) + " WHERE p.numero = ? ORDER BY p.id",
                            this::mapRow,
                            policyNumber)
                    .stream()
                    .findFirst()
                    .map(row -> toResponse(row, database, LocalDateTime.now()));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * In-force is checked to the hour, not the day: a policy expiring at 08:00 no longer covers at
     * 14:00. {@link #findPolicy} never filters, since an expired policy is a legitimate result there.
     */
    @Override
    public List<PolicyResponse> findPoliciesByInsured(String insuredId, boolean includeExpired) {
        // One instant for the whole call: the filter and the validity label must come from the same
        // clock, so filtering happens here instead of with Postgres' NOW().
        LocalDateTime now = LocalDateTime.now();
        List<PolicyResponse> policies = new ArrayList<>();
        for (InsurerDatabase database : insurerDatabases.forCaller()) {
            jdbc.query(POLICY_SELECT.formatted(database.schema()) + " WHERE a.documento = ?",
                            this::mapRow,
                            insuredId)
                    .forEach(row -> policies.add(toResponse(row, database, now)));
        }
        return policies.stream()
                .filter(p -> includeExpired || p.validity() != Validity.EXPIRED)
                // Sorted over the merged list: sorting in SQL would only order within each insurer.
                .sorted(Comparator
                        .comparing((PolicyResponse p) -> p.validity() == Validity.EXPIRED)
                        .thenComparing(PolicyResponse::policyNumber))
                .toList();
    }

    private PolicyResponse toResponse(PolicyRow row, InsurerDatabase database, LocalDateTime now) {
        List<Coverage> coverages = jdbc.query(
                """
                SELECT orden, nombre, suma_asegurada, franquicia_pct
                FROM %s.cobertura
                WHERE poliza_id = ?
                ORDER BY orden
                """.formatted(database.schema()),
                (rs, i) -> {
                    BigDecimal sum = rs.getBigDecimal("suma_asegurada");
                    BigDecimal pct = rs.getBigDecimal("franquicia_pct");
                    return Coverage.builder()
                            .code("COB-" + rs.getInt("orden"))
                            .description(rs.getString("nombre"))
                            .insuredAmount(sum)
                            .deductible(absoluteDeductible(sum, pct))
                            .deductiblePct(pct)
                            .build();
                },
                row.id());

        // The policy-level amounts come from the first coverage and are display-only. Anything that
        // decides (rules, Fast Track) must read `coverages` and pick the one for the claim cause.
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
                .validity(Validity.at(row.vigenciaDesde(), row.vigenciaHasta(), now))
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
