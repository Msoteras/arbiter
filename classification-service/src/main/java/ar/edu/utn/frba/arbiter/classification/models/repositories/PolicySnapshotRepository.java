package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.config.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The policy snapshot the classification actually ran on, so a later re-run can be explained after
 * the insurer's data changes. Written here because this is where the insurer DB feeds the decision.
 * Plain JDBC like {@link CaseOutcomeRepository}; cases-service's {@code PolicySnapshot} is the read side.
 */
@Repository
@RequiredArgsConstructor
public class PolicySnapshotRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param totalAmountClaimed what was paid across all the insured's policies, not this coverage's
     *                           remaining balance
     * @param eventsInYear       resolved here, where the history is, with the same window as the
     *                           events-per-year rule so the cap and the settlement agree
     * @param payload            the insurer DB's raw answer; the columns are its interpreted reading
     */
    public record Snapshot(
            String externalPolicyNumber,
            BigDecimal sumInsured,
            boolean inForce,
            boolean paymentsUpToDate,
            int previousClaims,
            BigDecimal totalAmountClaimed,
            LocalDateTime effectiveTo,
            BigDecimal installmentAmount,
            BigDecimal overdueBalance,
            Integer eventsInYear,
            String payload
    ) {}

    /**
     * One row per case, overwritten on reclassification: the table has no {@code case_id} (the link
     * is {@code cases.policy_snapshot_id}), so a row per run would be orphaned.
     */
    public void save(Long caseId, Snapshot snapshot) {
        Long existingId = currentSnapshotId(caseId);
        if (existingId != null) {
            update(existingId, snapshot);
            return;
        }
        jdbcTemplate.update(
                "UPDATE %s.cases SET policy_snapshot_id = ? WHERE id = ?".formatted(schema()),
                insert(snapshot), caseId);
    }

    /** Raw {@code JdbcTemplate} connections aren't tenant-routed, so every table is qualified. */
    private static String schema() {
        return TenantContext.schemaForSql();
    }

    private Long currentSnapshotId(Long caseId) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT policy_snapshot_id FROM %s.cases WHERE id = ?".formatted(schema()),
                (rs, rowNum) -> rs.getObject("policy_snapshot_id", Long.class),
                caseId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private Long insert(Snapshot snapshot) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO %s.policy_snapshot (external_policy_number, sum_insured, in_force,
                                                     payments_up_to_date, previous_claims,
                                                     total_amount_claimed, effective_to,
                                                     installment_amount, overdue_balance,
                                                     events_in_year, queried_at,
                                                     insurer_db_payload)
                             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?::jsonb)
                          RETURNING id
                        """.formatted(schema()),
                Long.class,
                snapshot.externalPolicyNumber(), snapshot.sumInsured(), snapshot.inForce(),
                snapshot.paymentsUpToDate(), snapshot.previousClaims(),
                snapshot.totalAmountClaimed(), snapshot.effectiveTo(),
                snapshot.installmentAmount(), snapshot.overdueBalance(),
                snapshot.eventsInYear(), snapshot.payload());
    }

    /** {@code queried_at} is overwritten too: the snapshot reflects the latest classification. */
    private void update(Long snapshotId, Snapshot snapshot) {
        jdbcTemplate.update("""
                        UPDATE %s.policy_snapshot
                           SET external_policy_number = ?, sum_insured = ?, in_force = ?,
                               payments_up_to_date = ?, previous_claims = ?,
                               total_amount_claimed = ?, effective_to = ?,
                               installment_amount = ?, overdue_balance = ?,
                               events_in_year = ?, queried_at = NOW(),
                               insurer_db_payload = ?::jsonb
                         WHERE id = ?
                        """.formatted(schema()),
                snapshot.externalPolicyNumber(), snapshot.sumInsured(), snapshot.inForce(),
                snapshot.paymentsUpToDate(), snapshot.previousClaims(),
                snapshot.totalAmountClaimed(), snapshot.effectiveTo(),
                snapshot.installmentAmount(), snapshot.overdueBalance(),
                snapshot.eventsInYear(), snapshot.payload(),
                snapshotId);
    }
}
