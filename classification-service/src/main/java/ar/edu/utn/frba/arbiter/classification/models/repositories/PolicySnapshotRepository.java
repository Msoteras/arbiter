package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.config.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The snapshot of the policy the classification ran on ({@code policy_snapshot}, the DER's
 * {@code poliza_consultada}).
 *
 * <p>This module writes it and not cases-service because this is where the data enters the
 * decision: {@code ClassificationOrchestrator.fetchContext()} queries the insurer's DB and two
 * scoring factors come from there ({@code policy_standing} ← {@code payments_up_to_date},
 * {@code claim_frequency} ← {@code previous_claims}). cases-service, on creation, doesn't touch the
 * insurer's DB at all: it works against the synced local copy. Having it take the snapshot would
 * give an audit record that doesn't match what the decision used, which is worse than none.
 *
 * <p>Without this the classification isn't reproducible: the insurer's DB is an external system
 * that keeps changing, so "they caught up on payments" or "another claim got settled" shift the
 * score of a re-run with no trace of what the first one saw — exactly what SSN Disposition 2/2023
 * asks to be able to show (D27).
 *
 * <p>Plain JDBC rather than an entity, for the same reason as {@link CaseOutcomeRepository}: mapping
 * a cases-service table here would leave it with two owning modules. cases-service's
 * {@code PolicySnapshot} entity is the <b>read</b> side.
 */
@Repository
@RequiredArgsConstructor
public class PolicySnapshotRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param inForce whether the policy temporally covered the event's date
     * @param totalAmountClaimed what those {@code previousClaims} were settled for — the count
     *                           alone doesn't say how big that history was. It's what was
     *                           <b>paid</b>, over every policy of the insured: it is not the
     *                           balance left on this coverage, and subtracting it from
     *                           {@code sumInsured} means nothing
     * @param effectiveTo when the policy's cover ends — the settlement counts the premium
     *                    installments still to fall due between the event and this date
     * @param installmentAmount what one of those installments costs
     * @param overdueBalance arrears already due, in money. {@code paymentsUpToDate} is the boolean
     *                       reading of the same fact: the rules only ask whether there's debt, the
     *                       settlement asks how much (clause 102, article 5)
     * @param eventsInYear which event of the rolling year this claim is — 1 is the first. Resolved
     *                     here and not at settlement time because this is where the history is, and
     *                     with the same 12-month window {@code TemporalRuleEvaluator} uses for
     *                     MAX_EVENTS_YEAR: the cap on events and the percentage payable must not
     *                     count differently
     * @param payload the insurer DB's raw answer, whole — it's the faithful record, of which the
     *                columns above are the already-interpreted reading
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
     * One row per case: if there's already a snapshot, it gets overwritten.
     *
     * <p>It's the handoff's decision (b). A case is reclassified when the insured completes
     * documentation, and hours pass between runs: the policy is the same. Storing a row per run
     * would add rows without a {@code case_id} — the table has none, the link is
     * {@code cases.policy_snapshot_id} — orphaned and hard to attribute. If the full history is
     * ever needed, it's adding {@code case_id} to the DER and only then here.
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

    /**
     * El esquema del tenant, explícito en cada query. {@code TenantConnectionProvider} solo
     * enruta las conexiones de Hibernate: este repositorio usa {@code JdbcTemplate} crudo, que
     * toma una conexión del pool con el {@code search_path} en {@code arbiter_common} — donde
     * {@code cases} y {@code policy_snapshot} no existen. Sin calificar, todo esto fallaba con
     * {@code relation "cases" does not exist} y el snapshot de auditoría (D27) no se escribía
     * nunca, en silencio, porque el llamador lo trata como best-effort.
     */
    private static String schema() {
        return TenantContext.schemaForSql();
    }

    /** Null if the case has no snapshot yet, or isn't in this schema. */
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

    /** {@code queried_at} se pisa también: la foto vigente es la de la última clasificación. */
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
