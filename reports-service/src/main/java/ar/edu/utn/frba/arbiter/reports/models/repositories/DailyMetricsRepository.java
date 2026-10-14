package ar.edu.utn.frba.arbiter.reports.models.repositories;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.ExpertVerdict;
import ar.edu.utn.frba.arbiter.common.enums.SettlementStatus;
import ar.edu.utn.frba.arbiter.reports.dto.MetricCount;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsFilter;
import ar.edu.utn.frba.arbiter.reports.dto.ReportPeriod;
import ar.edu.utn.frba.arbiter.reports.dto.SettledAmounts;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;

/**
 * The stored figures of closed days ({@code metrics_day} and {@code metrics_daily_*}), the only tables
 * this module owns and writes.
 *
 * <p>One row per day holding sums and counts, never a rate or an average: an average can't be combined
 * with another day's, its numerator and denominator can.
 */
@Repository
@RequiredArgsConstructor
public class DailyMetricsRepository {

    private static final RowMapper<MetricCount> COUNT_ROW =
            (rs, rowNum) -> new MetricCount(rs.getString("label"), rs.getLong("total"));

    private final EntityManager entityManager;

    /** Null means "every one". */
    public record Cut(Long branchId, Long analystId, String claimCause) {

        public static Cut of(MetricsFilter filter) {
            return new Cut(filter.branchId(), filter.analystId(), null);
        }
    }

    public record DailyIntake(LocalDate day, long reported, long fastTrack) {}

    /** One row per day and final status; the seconds are sums, not averages. */
    public record DailyResolution(
            LocalDate day,
            String status,
            long resolved,
            double totalSeconds,
            double waitingSeconds,
            long fastTrack,
            double fastTrackSeconds,
            long onTime,
            long reopened,
            long agreementEligible,
            long agreementAgreed,
            long fraudDetermined,
            long fraudBackedByExpert,
            BigDecimal fraudClaimedAmount
    ) {}

    /**
     * Stores the days of the period not stored yet. A day with work in flight (a claim awaiting
     * classification, a settlement awaiting the referent) is skipped: storing it would freeze a number
     * about to change.
     *
     * <p>The day is claimed in {@code metrics_day} first, so a concurrent request blocks on the primary
     * key instead of storing it twice.
     *
     * @return whether every day of the period is stored once this returns
     */
    @Transactional
    public boolean store(ReportPeriod period, ZoneId zone) {
        // First, so a stored period never goes back to the cases.
        if (storedDays(period) == period.days()) {
            return true;
        }
        MapSqlParameterSource params = window(period, zone)
                .addValue("unclassified", List.of(CaseStatus.PENDING_CLASSIFICATION.name(), CaseStatus.CLASSIFICATION_FAILED.name()))
                .addValue("pendingAuthorization", SettlementStatus.PENDING_AUTHORIZATION.name());
        List<LocalDate> claimed = query(template -> template.queryForList("""
                INSERT INTO metrics_day (day)
                SELECT d::date
                  FROM generate_series(CAST(:fromDay AS date), CAST(:toDay AS date),
                                       INTERVAL '1 day') AS d
                 WHERE d::date NOT IN (
                           SELECT (c.reported_at AT TIME ZONE :zone)::date
                             FROM cases c
                             JOIN case_status s ON s.id = c.current_status_id
                            WHERE s.name IN (:unclassified)
                              AND c.reported_at >= :from AND c.reported_at < :to)
                   AND d::date NOT IN (
                           SELECT (st.confirmed_at AT TIME ZONE :zone)::date
                             FROM case_settlement st
                            WHERE st.status = :pendingAuthorization
                              AND st.confirmed_at >= :from AND st.confirmed_at < :to)
                ON CONFLICT (day) DO NOTHING
                RETURNING day""", params, LocalDate.class));
        if (!claimed.isEmpty()) {
            fill(window(period, zone).addValue("days", claimed));
        }
        return storedDays(period) == period.days();
    }

    @Transactional(readOnly = true)
    public int storedDays(ReportPeriod period) {
        Integer days = query(template -> template.queryForObject(
                "SELECT count(*) FROM metrics_day WHERE day >= :fromDay AND day <= :toDay",
                days(period), Integer.class));
        return days == null ? 0 : days;
    }

    /** The daily rows go with their day (ON DELETE CASCADE). */
    @Transactional
    public void forget(ReportPeriod period) {
        query(template -> template.update(
                "DELETE FROM metrics_day WHERE day >= :fromDay AND day <= :toDay", days(period)));
    }

    @Transactional(readOnly = true)
    public List<DailyIntake> intake(ReportPeriod period, Cut cut) {
        String sql = "SELECT m.day, sum(m.reported) AS reported, sum(m.fast_track) AS fast_track"
                + from("metrics_daily_intake", "", cut) + " GROUP BY m.day ORDER BY m.day";
        return query(template -> template.query(sql, cut(period, cut), (rs, rowNum) -> new DailyIntake(
                rs.getObject("day", LocalDate.class), rs.getLong("reported"), rs.getLong("fast_track"))));
    }

    @Transactional(readOnly = true)
    public List<MetricCount> reportedByBranch(ReportPeriod period, Cut cut) {
        String sql = "SELECT b.name AS label, sum(m.reported) AS total"
                + from("metrics_daily_intake", "\n  JOIN branch b ON b.id = cc.branch_id", cut)
                + " GROUP BY b.name ORDER BY total DESC, label";
        return query(template -> template.query(sql, cut(period, cut), COUNT_ROW));
    }

    @Transactional(readOnly = true)
    public List<DailyResolution> resolution(ReportPeriod period, Cut cut) {
        String sql = """
                SELECT m.day, m.final_status,
                       sum(m.resolved) AS resolved,
                       sum(m.total_seconds) AS total_seconds,
                       sum(m.waiting_seconds) AS waiting_seconds,
                       sum(m.fast_track) AS fast_track,
                       sum(m.fast_track_seconds) AS fast_track_seconds,
                       sum(m.on_time) AS on_time,
                       sum(m.reopened) AS reopened,
                       sum(m.agreement_eligible) AS agreement_eligible,
                       sum(m.agreement_agreed) AS agreement_agreed,
                       sum(m.fraud_determined) AS fraud_determined,
                       sum(m.fraud_backed_by_expert) AS fraud_backed_by_expert,
                       sum(m.fraud_claimed_amount) AS fraud_claimed_amount"""
                + from("metrics_daily_resolution", "", cut)
                + " GROUP BY m.day, m.final_status ORDER BY m.day, m.final_status";
        return query(template -> template.query(sql, cut(period, cut), (rs, rowNum) -> new DailyResolution(
                rs.getObject("day", LocalDate.class),
                rs.getString("final_status"),
                rs.getLong("resolved"),
                rs.getDouble("total_seconds"),
                rs.getDouble("waiting_seconds"),
                rs.getLong("fast_track"),
                rs.getDouble("fast_track_seconds"),
                rs.getLong("on_time"),
                rs.getLong("reopened"),
                rs.getLong("agreement_eligible"),
                rs.getLong("agreement_agreed"),
                rs.getLong("fraud_determined"),
                rs.getLong("fraud_backed_by_expert"),
                rs.getBigDecimal("fraud_claimed_amount"))));
    }

    @Transactional(readOnly = true)
    public List<MetricCount> resolvedByClaimCause(ReportPeriod period, Cut cut) {
        String sql = "SELECT cc.name AS label, sum(m.resolved) AS total"
                + from("metrics_daily_resolution", "", cut)
                + " GROUP BY cc.name ORDER BY total DESC, label";
        return query(template -> template.query(sql, cut(period, cut), COUNT_ROW));
    }

    @Transactional(readOnly = true)
    public SettledAmounts settled(ReportPeriod period, Cut cut) {
        String sql = """
                SELECT COALESCE(sum(m.settlements), 0) AS settlements,
                       COALESCE(sum(m.settled_amount), 0) AS settled,
                       COALESCE(sum(m.claimed_amount), 0) AS claimed,
                       COALESCE(sum(m.claimed_cases), 0) AS claimed_cases,
                       COALESCE(sum(m.deductible_amount), 0) AS deductible,
                       COALESCE(sum(m.installments_amount), 0) AS installments,
                       COALESCE(sum(m.overdue_amount), 0) AS overdue"""
                + from("metrics_daily_settlement", "", cut);
        return query(template -> template.queryForObject(sql, cut(period, cut), (rs, rowNum) ->
                SettledAmounts.of(
                        rs.getLong("settlements"),
                        rs.getBigDecimal("settled"),
                        rs.getBigDecimal("claimed"),
                        rs.getLong("claimed_cases"),
                        rs.getBigDecimal("deductible"),
                        rs.getBigDecimal("installments"),
                        rs.getBigDecimal("overdue"))));
    }

    /** The same definitions as {@link ClaimMetricsRepository}'s live queries, grouped by day. */
    private void fill(MapSqlParameterSource params) {
        params.addValue("authorized", SettlementStatus.AUTHORIZED.name())
                .addValue("pausing", CaseStatus.pausingTheTerm().stream().map(Enum::name).toList())
                .addValue("recommendApprove", Classification.LLM_RECOMIENDA_APROBAR.name())
                .addValue("recommendReject", Classification.LLM_NO_RECOMIENDA_APROBAR.name())
                .addValue("approved", CaseStatus.APPROVED.name())
                .addValue("rejected", CaseStatus.REJECTED.name())
                .addValue("fraudConfirmed", ExpertVerdict.FRAUD_CONFIRMED.name());

        query(template -> template.update("""
                INSERT INTO metrics_daily_intake (day, claim_cause_id, analyst_id, reported, fast_track)
                SELECT (c.reported_at AT TIME ZONE :zone)::date, c.claim_cause_id, c.analyst_id,
                       count(*),
                       count(*) FILTER (WHERE c.rules_classification = 'FAST_TRACK')
                  FROM cases c
                 WHERE c.reported_at >= :from AND c.reported_at < :to
                   AND (c.reported_at AT TIME ZONE :zone)::date IN (:days)
                 GROUP BY 1, 2, 3""", params));

        query(template -> template.update(CaseResolutionSql.RESOLUTION_CTE + ",\n"
                + CaseResolutionSql.WAITING_CTE + ",\n" + ClaimMetricsRepository.LATEST_LLM_CTE + """

                INSERT INTO metrics_daily_resolution (
                       day, claim_cause_id, analyst_id, final_status, resolved, total_seconds,
                       waiting_seconds, fast_track, fast_track_seconds, on_time, reopened,
                       agreement_eligible, agreement_agreed, fraud_determined,
                       fraud_backed_by_expert, fraud_claimed_amount)
                SELECT (r.resolved_at AT TIME ZONE :zone)::date, c.claim_cause_id, c.analyst_id, s.name,
                       count(*),
                       sum(EXTRACT(EPOCH FROM (r.resolved_at - c.reported_at))),
                       sum(COALESCE(wt.waiting_seconds, 0)),
                       count(*) FILTER (WHERE c.rules_classification = 'FAST_TRACK'),
                       COALESCE(sum(EXTRACT(EPOCH FROM (r.resolved_at - c.reported_at)))
                           FILTER (WHERE c.rules_classification = 'FAST_TRACK'), 0),
                       count(*) FILTER (
                           WHERE (r.resolved_at AT TIME ZONE :zone)::date <= c.response_deadline),
                       count(*) FILTER (WHERE EXISTS (
                           SELECT 1
                             FROM case_status_history h
                             JOIN case_status hs ON hs.id = h.initial_status_id AND hs.is_final
                             JOIN case_status hf ON hf.id = h.final_status_id AND NOT hf.is_final
                            WHERE h.case_id = c.id)),
                       count(*) FILTER (
                           WHERE c.rules_classification IS NULL
                             AND l.recommendation IN (:recommendApprove, :recommendReject)),
                       count(*) FILTER (
                           WHERE c.rules_classification IS NULL
                             AND ((l.recommendation = :recommendApprove AND s.name = :approved)
                               OR (l.recommendation = :recommendReject  AND s.name = :rejected))),
                       count(*) FILTER (WHERE c.fraud_determined),
                       count(*) FILTER (WHERE c.fraud_determined AND EXISTS (
                           SELECT 1 FROM case_referral ea
                            WHERE ea.case_id = c.id AND ea.verdict = :fraudConfirmed)),
                       COALESCE(sum(c.claimed_amount) FILTER (WHERE c.fraud_determined), 0)
                  FROM cases c
                  JOIN case_status s ON s.id = c.current_status_id AND s.is_final
                  JOIN resolution r  ON r.case_id = c.id
                  LEFT JOIN waiting wt   ON wt.case_id = c.id
                  LEFT JOIN latest_llm l ON l.case_id = c.id
                 WHERE r.resolved_at >= :from AND r.resolved_at < :to
                   AND (r.resolved_at AT TIME ZONE :zone)::date IN (:days)
                 GROUP BY 1, 2, 3, 4""", params));

        query(template -> template.update("""
                INSERT INTO metrics_daily_settlement (
                       day, claim_cause_id, analyst_id, settlements, settled_amount, claimed_amount,
                       claimed_cases, deductible_amount, installments_amount, overdue_amount)
                SELECT (st.confirmed_at AT TIME ZONE :zone)::date, c.claim_cause_id, c.analyst_id,
                       count(*),
                       COALESCE(sum(st.settled_amount), 0),
                       COALESCE(sum(c.claimed_amount), 0),
                       count(c.claimed_amount),
                       COALESCE(sum(st.deductible_amount), 0),
                       COALESCE(sum(st.pending_installments_amount), 0),
                       COALESCE(sum(st.overdue_balance_amount), 0)
                  FROM cases c
                  JOIN case_settlement st ON st.case_id = c.id
                 WHERE st.confirmed_at >= :from AND st.confirmed_at < :to
                   AND st.status = :authorized
                   AND (st.confirmed_at AT TIME ZONE :zone)::date IN (:days)
                 GROUP BY 1, 2, 3""", params));
    }

    /** The branch hangs off the claim cause, so every read joins it. */
    private static String from(String table, String joins, Cut cut) {
        StringBuilder sql = new StringBuilder("\n  FROM ").append(table).append(" m")
                .append("\n  JOIN claim_cause cc ON cc.id = m.claim_cause_id")
                .append(joins)
                .append("\n WHERE m.day >= :fromDay AND m.day <= :toDay");
        // Appended rather than `:branchId IS NULL OR ...`: Postgres can't infer the type of a
        // parameter only ever compared to NULL.
        if (cut.branchId() != null) {
            sql.append("\n   AND cc.branch_id = :branchId");
        }
        if (cut.analystId() != null) {
            sql.append("\n   AND m.analyst_id = :analystId");
        }
        if (cut.claimCause() != null) {
            sql.append("\n   AND cc.name = :claimCause");
        }
        return sql.toString();
    }

    private static MapSqlParameterSource cut(ReportPeriod period, Cut cut) {
        MapSqlParameterSource params = days(period);
        if (cut.branchId() != null) {
            params.addValue("branchId", cut.branchId());
        }
        if (cut.analystId() != null) {
            params.addValue("analystId", cut.analystId());
        }
        if (cut.claimCause() != null) {
            params.addValue("claimCause", cut.claimCause());
        }
        return params;
    }

    private static MapSqlParameterSource days(ReportPeriod period) {
        return new MapSqlParameterSource()
                .addValue("fromDay", period.from())
                .addValue("toDay", period.to());
    }

    private static MapSqlParameterSource window(ReportPeriod period, ZoneId zone) {
        return days(period)
                .addValue("from", OffsetDateTime.ofInstant(period.start(zone), ZoneOffset.UTC))
                .addValue("to", OffsetDateTime.ofInstant(period.end(zone), ZoneOffset.UTC))
                .addValue("zone", zone.getId());
    }

    private <T> T query(Function<NamedParameterJdbcTemplate, T> work) {
        // suppressClose: the connection is Hibernate's and Hibernate closes it.
        return entityManager.unwrap(Session.class).doReturningWork(connection ->
                work.apply(new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection, true))));
    }
}
