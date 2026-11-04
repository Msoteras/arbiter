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
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;

import static ar.edu.utn.frba.arbiter.reports.models.repositories.DailyMetric.*;

/**
 * The stored figures of closed days ({@code metrics_day} and {@code metrics_daily_value}), the only
 * tables this module owns and writes.
 *
 * <p>One row per day, metric, claim cause and analyst, holding a sum or a count, never a rate or an
 * average: an average can't be combined with another day's, its numerator and denominator can. The
 * catalog of metrics is {@link DailyMetric}; adding one needs no DDL.
 */
@Repository
@RequiredArgsConstructor
public class DailyMetricsRepository {

    private static final RowMapper<MetricCount> COUNT_ROW =
            (rs, rowNum) -> new MetricCount(rs.getString("label"), rs.getLong("total"));

    private static final List<DailyMetric> INTAKE = List.of(REPORTED, REPORTED_FAST_TRACK);

    private static final List<DailyMetric> RESOLUTION = List.of(
            RESOLVED, TOTAL_SECONDS, WAITING_SECONDS, RESOLVED_FAST_TRACK, FAST_TRACK_SECONDS, ON_TIME,
            REOPENED, AGREEMENT_ELIGIBLE, AGREEMENT_AGREED, FRAUD_DETERMINED, FRAUD_BACKED_BY_EXPERT,
            FRAUD_CLAIMED_AMOUNT);

    private static final List<DailyMetric> SETTLEMENT = List.of(
            SETTLEMENTS, SETTLED_AMOUNT, CLAIMED_AMOUNT, CLAIMED_CASES, DEDUCTIBLE_AMOUNT,
            INSTALLMENTS_AMOUNT, OVERDUE_AMOUNT);

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
     * Stores the days of the period not stored yet, and stores again the ones stored with an older
     * {@link DailyMetric#DEFINITIONS_VERSION}. A day with work in flight (a claim awaiting
     * classification, a settlement awaiting the referent) is skipped: storing it would freeze a number
     * about to change.
     *
     * <p>The day is claimed in {@code metrics_day} first, so a concurrent request blocks on the primary
     * key instead of storing it twice.
     *
     * @return whether every day of the period is stored, at the current version, once this returns
     */
    @Transactional
    public boolean store(ReportPeriod period, ZoneId zone) {
        // First, so a stored period never goes back to the cases.
        if (storedDays(period) == period.days()) {
            return true;
        }
        MapSqlParameterSource params = window(period, zone)
                .addValue("version", DEFINITIONS_VERSION)
                .addValue("unclassified", List.of(CaseStatus.PENDING_CLASSIFICATION.name(), CaseStatus.CLASSIFICATION_FAILED.name()))
                .addValue("pendingAuthorization", SettlementStatus.PENDING_AUTHORIZATION.name());
        List<LocalDate> claimed = query(template -> template.queryForList("""
                INSERT INTO metrics_day (day, definitions_version)
                SELECT d::date, :version
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
                ON CONFLICT (day) DO UPDATE
                   SET definitions_version = EXCLUDED.definitions_version, computed_at = NOW()
                 WHERE metrics_day.definitions_version < EXCLUDED.definitions_version
                RETURNING day""", params, LocalDate.class));
        if (!claimed.isEmpty()) {
            fill(window(period, zone).addValue("days", claimed));
        }
        return storedDays(period) == period.days();
    }

    /** A day stored with an older version doesn't count: the next {@link #store} redoes it. */
    @Transactional(readOnly = true)
    public int storedDays(ReportPeriod period) {
        Integer days = query(template -> template.queryForObject("""
                SELECT count(*) FROM metrics_day
                 WHERE day >= :fromDay AND day <= :toDay AND definitions_version >= :version""",
                days(period).addValue("version", DEFINITIONS_VERSION), Integer.class));
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
        String sql = "SELECT m.day, " + totals(INTAKE)
                + from("", cut) + among(INTAKE) + " GROUP BY m.day ORDER BY m.day";
        return query(template -> template.query(sql, cut(period, cut), (rs, rowNum) -> new DailyIntake(
                rs.getObject("day", LocalDate.class),
                rs.getLong(column(REPORTED)),
                rs.getLong(column(REPORTED_FAST_TRACK)))));
    }

    @Transactional(readOnly = true)
    public List<MetricCount> reportedByBranch(ReportPeriod period, Cut cut) {
        String sql = "SELECT b.name AS label, sum(m.value) AS total"
                + from("\n  JOIN branch b ON b.id = cc.branch_id", cut) + among(List.of(REPORTED))
                + " GROUP BY b.name ORDER BY total DESC, label";
        return query(template -> template.query(sql, cut(period, cut), COUNT_ROW));
    }

    @Transactional(readOnly = true)
    public List<DailyResolution> resolution(ReportPeriod period, Cut cut) {
        String sql = "SELECT m.day, m.segment AS final_status, " + totals(RESOLUTION)
                + from("", cut) + among(RESOLUTION)
                + " GROUP BY m.day, m.segment ORDER BY m.day, m.segment";
        return query(template -> template.query(sql, cut(period, cut), (rs, rowNum) -> new DailyResolution(
                rs.getObject("day", LocalDate.class),
                rs.getString("final_status"),
                rs.getLong(column(RESOLVED)),
                rs.getDouble(column(TOTAL_SECONDS)),
                rs.getDouble(column(WAITING_SECONDS)),
                rs.getLong(column(RESOLVED_FAST_TRACK)),
                rs.getDouble(column(FAST_TRACK_SECONDS)),
                rs.getLong(column(ON_TIME)),
                rs.getLong(column(REOPENED)),
                rs.getLong(column(AGREEMENT_ELIGIBLE)),
                rs.getLong(column(AGREEMENT_AGREED)),
                rs.getLong(column(FRAUD_DETERMINED)),
                rs.getLong(column(FRAUD_BACKED_BY_EXPERT)),
                amount(rs, FRAUD_CLAIMED_AMOUNT))));
    }

    @Transactional(readOnly = true)
    public List<MetricCount> resolvedByClaimCause(ReportPeriod period, Cut cut) {
        String sql = "SELECT cc.name AS label, sum(m.value) AS total"
                + from("", cut) + among(List.of(RESOLVED))
                + " GROUP BY cc.name ORDER BY total DESC, label";
        return query(template -> template.query(sql, cut(period, cut), COUNT_ROW));
    }

    @Transactional(readOnly = true)
    public SettledAmounts settled(ReportPeriod period, Cut cut) {
        String sql = "SELECT " + totals(SETTLEMENT) + from("", cut) + among(SETTLEMENT);
        return query(template -> template.queryForObject(sql, cut(period, cut), (rs, rowNum) ->
                SettledAmounts.of(
                        rs.getLong(column(SETTLEMENTS)),
                        amount(rs, SETTLED_AMOUNT),
                        amount(rs, CLAIMED_AMOUNT),
                        rs.getLong(column(CLAIMED_CASES)),
                        amount(rs, DEDUCTIBLE_AMOUNT),
                        amount(rs, INSTALLMENTS_AMOUNT),
                        amount(rs, OVERDUE_AMOUNT))));
    }

    /**
     * The same definitions as {@link ClaimMetricsRepository}'s live queries, grouped by day. Each source
     * computes its figures as columns and turns them into one row per metric with the LATERAL VALUES:
     * a new metric is a column and a line there.
     */
    private void fill(MapSqlParameterSource params) {
        params.addValue("authorized", SettlementStatus.AUTHORIZED.name())
                .addValue("pausing", CaseStatus.pausingTheTerm().stream().map(Enum::name).toList())
                .addValue("recommendApprove", Classification.LLM_RECOMIENDA_APROBAR.name())
                .addValue("recommendReject", Classification.LLM_NO_RECOMIENDA_APROBAR.name())
                .addValue("approved", CaseStatus.APPROVED.name())
                .addValue("rejected", CaseStatus.REJECTED.name())
                .addValue("fraudConfirmed", ExpertVerdict.FRAUD_CONFIRMED.name());

        // A day stored with an older version is being stored again.
        query(template -> template.update("DELETE FROM metrics_daily_value WHERE day IN (:days)", params));

        query(template -> template.update("""
                INSERT INTO metrics_daily_value (day, metric, claim_cause_id, analyst_id, segment, value)
                SELECT g.day, v.metric, g.claim_cause_id, g.analyst_id, '', v.value
                  FROM (SELECT (c.reported_at AT TIME ZONE :zone)::date AS day, c.claim_cause_id,
                               c.analyst_id,
                               count(*) AS reported,
                               count(*) FILTER (WHERE c.rules_classification = 'FAST_TRACK') AS fast_track
                          FROM cases c
                         WHERE c.reported_at >= :from AND c.reported_at < :to
                           AND (c.reported_at AT TIME ZONE :zone)::date IN (:days)
                         GROUP BY 1, 2, 3) g
                 CROSS JOIN LATERAL (VALUES
                       ('REPORTED',            g.reported),
                       ('REPORTED_FAST_TRACK', g.fast_track)
                 ) AS v(metric, value)""", params));

        // The seconds are cast because EXTRACT returns double precision before Postgres 14, and a
        // double in the VALUES would turn every amount into one.
        query(template -> template.update(CaseResolutionSql.RESOLUTION_CTE + ",\n"
                + CaseResolutionSql.WAITING_CTE + ",\n" + ClaimMetricsRepository.LATEST_LLM_CTE + """

                INSERT INTO metrics_daily_value (day, metric, claim_cause_id, analyst_id, segment, value)
                SELECT g.day, v.metric, g.claim_cause_id, g.analyst_id, g.final_status, v.value
                  FROM (SELECT (r.resolved_at AT TIME ZONE :zone)::date AS day, c.claim_cause_id,
                               c.analyst_id, s.name AS final_status,
                               count(*) AS resolved,
                               CAST(sum(EXTRACT(EPOCH FROM (r.resolved_at - c.reported_at))) AS numeric)
                                   AS total_seconds,
                               CAST(sum(COALESCE(wt.waiting_seconds, 0)) AS numeric) AS waiting_seconds,
                               count(*) FILTER (WHERE c.rules_classification = 'FAST_TRACK') AS fast_track,
                               CAST(COALESCE(sum(EXTRACT(EPOCH FROM (r.resolved_at - c.reported_at)))
                                   FILTER (WHERE c.rules_classification = 'FAST_TRACK'), 0) AS numeric)
                                   AS fast_track_seconds,
                               count(*) FILTER (
                                   WHERE (r.resolved_at AT TIME ZONE :zone)::date <= c.response_deadline)
                                   AS on_time,
                               count(*) FILTER (WHERE EXISTS (
                                   SELECT 1
                                     FROM case_status_history h
                                     JOIN case_status hs ON hs.id = h.initial_status_id AND hs.is_final
                                     JOIN case_status hf ON hf.id = h.final_status_id AND NOT hf.is_final
                                    WHERE h.case_id = c.id)) AS reopened,
                               count(*) FILTER (
                                   WHERE c.rules_classification IS NULL
                                     AND l.recommendation IN (:recommendApprove, :recommendReject))
                                   AS agreement_eligible,
                               count(*) FILTER (
                                   WHERE c.rules_classification IS NULL
                                     AND ((l.recommendation = :recommendApprove AND s.name = :approved)
                                       OR (l.recommendation = :recommendReject  AND s.name = :rejected)))
                                   AS agreement_agreed,
                               count(*) FILTER (WHERE c.fraud_determined) AS fraud_determined,
                               count(*) FILTER (WHERE c.fraud_determined AND EXISTS (
                                   SELECT 1 FROM case_referral ea
                                    WHERE ea.case_id = c.id AND ea.verdict = :fraudConfirmed))
                                   AS fraud_backed_by_expert,
                               COALESCE(sum(c.claimed_amount) FILTER (WHERE c.fraud_determined), 0)
                                   AS fraud_claimed_amount
                          FROM cases c
                          JOIN case_status s ON s.id = c.current_status_id AND s.is_final
                          JOIN resolution r  ON r.case_id = c.id
                          LEFT JOIN waiting wt   ON wt.case_id = c.id
                          LEFT JOIN latest_llm l ON l.case_id = c.id
                         WHERE r.resolved_at >= :from AND r.resolved_at < :to
                           AND (r.resolved_at AT TIME ZONE :zone)::date IN (:days)
                         GROUP BY 1, 2, 3, 4) g
                 CROSS JOIN LATERAL (VALUES
                       ('RESOLVED',               g.resolved),
                       ('TOTAL_SECONDS',          g.total_seconds),
                       ('WAITING_SECONDS',        g.waiting_seconds),
                       ('RESOLVED_FAST_TRACK',    g.fast_track),
                       ('FAST_TRACK_SECONDS',     g.fast_track_seconds),
                       ('ON_TIME',                g.on_time),
                       ('REOPENED',               g.reopened),
                       ('AGREEMENT_ELIGIBLE',     g.agreement_eligible),
                       ('AGREEMENT_AGREED',       g.agreement_agreed),
                       ('FRAUD_DETERMINED',       g.fraud_determined),
                       ('FRAUD_BACKED_BY_EXPERT', g.fraud_backed_by_expert),
                       ('FRAUD_CLAIMED_AMOUNT',   g.fraud_claimed_amount)
                 ) AS v(metric, value)""", params));

        query(template -> template.update("""
                INSERT INTO metrics_daily_value (day, metric, claim_cause_id, analyst_id, segment, value)
                SELECT g.day, v.metric, g.claim_cause_id, g.analyst_id, '', v.value
                  FROM (SELECT (st.confirmed_at AT TIME ZONE :zone)::date AS day, c.claim_cause_id,
                               c.analyst_id,
                               count(*) AS settlements,
                               COALESCE(sum(st.settled_amount), 0) AS settled_amount,
                               COALESCE(sum(c.claimed_amount), 0) AS claimed_amount,
                               count(c.claimed_amount) AS claimed_cases,
                               COALESCE(sum(st.deductible_amount), 0) AS deductible_amount,
                               COALESCE(sum(st.pending_installments_amount), 0) AS installments_amount,
                               COALESCE(sum(st.overdue_balance_amount), 0) AS overdue_amount
                          FROM cases c
                          JOIN case_settlement st ON st.case_id = c.id
                         WHERE st.confirmed_at >= :from AND st.confirmed_at < :to
                           AND st.status = :authorized
                           AND (st.confirmed_at AT TIME ZONE :zone)::date IN (:days)
                         GROUP BY 1, 2, 3) g
                 CROSS JOIN LATERAL (VALUES
                       ('SETTLEMENTS',         g.settlements),
                       ('SETTLED_AMOUNT',      g.settled_amount),
                       ('CLAIMED_AMOUNT',      g.claimed_amount),
                       ('CLAIMED_CASES',       g.claimed_cases),
                       ('DEDUCTIBLE_AMOUNT',   g.deductible_amount),
                       ('INSTALLMENTS_AMOUNT', g.installments_amount),
                       ('OVERDUE_AMOUNT',      g.overdue_amount)
                 ) AS v(metric, value)""", params));
    }

    /** One column per metric, named after it; 0 where the cut has no row for it. */
    private static String totals(List<DailyMetric> metrics) {
        return metrics.stream()
                .map(metric -> "COALESCE(sum(m.value) FILTER (WHERE m.metric = '" + metric.name() + "'), 0) AS "
                        + column(metric))
                .collect(Collectors.joining(",\n       "));
    }

    /** Keeps a source's days to its own rows, so a day with only another source's rows isn't listed. */
    private static String among(List<DailyMetric> metrics) {
        return metrics.stream()
                .map(metric -> "'" + metric.name() + "'")
                .collect(Collectors.joining(", ", "\n   AND m.metric IN (", ")"));
    }

    private static String column(DailyMetric metric) {
        return metric.name().toLowerCase(Locale.ROOT);
    }

    /** value carries four decimals for the seconds; money goes back to the cents it was stored with. */
    private static BigDecimal amount(ResultSet rs, DailyMetric metric) throws SQLException {
        return rs.getBigDecimal(column(metric)).setScale(2, RoundingMode.HALF_UP);
    }

    /** The branch hangs off the claim cause, so every read joins it. */
    private static String from(String joins, Cut cut) {
        StringBuilder sql = new StringBuilder("\n  FROM metrics_daily_value m")
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
