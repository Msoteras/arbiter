package ar.edu.utn.frba.arbiter.reports.models.repositories;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.ExpertVerdict;
import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import ar.edu.utn.frba.arbiter.common.enums.SettlementStatus;
import ar.edu.utn.frba.arbiter.reports.dto.DerivationTurnaround;
import ar.edu.utn.frba.arbiter.reports.dto.FastTrackImpact;
import ar.edu.utn.frba.arbiter.reports.dto.FraudDetection;
import ar.edu.utn.frba.arbiter.reports.dto.IntakeFunnel;
import ar.edu.utn.frba.arbiter.reports.dto.LegalDeadline;
import ar.edu.utn.frba.arbiter.reports.dto.MetricCount;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsFilter;
import ar.edu.utn.frba.arbiter.reports.dto.RecommendationAgreement;
import ar.edu.utn.frba.arbiter.reports.dto.ReopeningRate;
import ar.edu.utn.frba.arbiter.reports.dto.SettledAmounts;
import ar.edu.utn.frba.arbiter.reports.dto.TimelineGranularity;
import ar.edu.utn.frba.arbiter.reports.dto.TimelinePoint;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;

/**
 * The dashboard's aggregations. On Hibernate's connection, not the pool's: unqualified table names
 * resolve through the tenant {@code search_path} (over the {@code DataSource} they fail in production
 * and pass in tests, where the schema is flat).
 */
@Repository
@RequiredArgsConstructor
public class ClaimMetricsRepository {

    /** The newest model run per case; analyses are append-only. */
    private static final String LATEST_LLM_CTE = """
            latest_llm AS (
                SELECT DISTINCT ON (case_id) case_id, recommendation
                  FROM llm_analysis
                 ORDER BY case_id, id DESC
            )""";

    // The branch filter hangs off claim cause; the leading newline separates it from the SELECT.
    private static final String FROM_CASES = "\n" + """
              FROM cases c
              JOIN claim_cause cc ON cc.id = c.claim_cause_id""";

    /** Claims FILED in the period: the population every distribution is drawn from. */
    private static final String REPORTED_WINDOW = " WHERE c.reported_at >= :from AND c.reported_at < :to";

    /** How classification-service writes a failed rule: its table format, so a literal, not an enum. */
    private static final String FAILED = "FAIL";

    private static final RowMapper<MetricCount> COUNT_ROW =
            (rs, rowNum) -> new MetricCount(rs.getString("label"), rs.getLong("total"));

    private final EntityManager entityManager;

    /** @param fastTrack the case flag is the only trace: a Fast Track leaves no analysis row */
    public record IntakeTotals(long reported, long fastTrack) {}

    public record ResolvedTotals(String status, long count, Double averageSeconds) {}

    @Transactional(readOnly = true)
    public IntakeTotals intakeTotals(Instant from, Instant to, MetricsFilter filter) {
        String sql = "SELECT count(*) AS reported, count(*) FILTER (WHERE c.was_fast_track) AS fast_track"
                + FROM_CASES + REPORTED_WINDOW + filters(filter);
        return query(template -> template.queryForObject(sql, period(from, to, filter),
                (rs, rowNum) -> new IntakeTotals(rs.getLong("reported"), rs.getLong("fast_track"))));
    }

    @Transactional(readOnly = true)
    public IntakeFunnel intakeFunnel(Instant from, Instant to, MetricsFilter filter) {
        String sql = "WITH " + LATEST_LLM_CTE + """

                SELECT count(*) AS reported,
                       count(*) FILTER (WHERE l.case_id IS NOT NULL) AS analyzed,
                       count(*) FILTER (WHERE c.classification_id IS NOT NULL) AS decided,
                       count(*) FILTER (WHERE c.was_fast_track) AS fast_track,
                       count(*) FILTER (WHERE NOT s.is_final) AS still_open"""
                + FROM_CASES + """

                  JOIN case_status s ON s.id = c.current_status_id
                  LEFT JOIN latest_llm l ON l.case_id = c.id"""
                + REPORTED_WINDOW + filters(filter);
        return query(template -> template.queryForObject(sql, period(from, to, filter),
                (rs, rowNum) -> new IntakeFunnel(
                        rs.getLong("reported"),
                        rs.getLong("analyzed"),
                        rs.getLong("decided"),
                        rs.getLong("fast_track"),
                        rs.getLong("still_open"))));
    }

    /**
     * Over the claims resolved in the period. Agreement is judged by final status, not the decision
     * string, which holds "APPROVE" from the app and "APROBAR" from seed data.
     */
    @Transactional(readOnly = true)
    public RecommendationAgreement recommendationAgreement(Instant from, Instant to, MetricsFilter filter) {
        String sql = CaseResolutionSql.RESOLUTION_CTE + ",\n" + LATEST_LLM_CTE + """

                SELECT count(*) AS decided,
                       count(*) FILTER (
                           WHERE (l.recommendation = :recommendApprove AND s.name = :approved)
                              OR (l.recommendation = :recommendReject  AND s.name = :rejected)
                       ) AS agreed"""
                + FROM_CASES + """

                  JOIN case_status s ON s.id = c.current_status_id AND s.is_final
                  JOIN resolution r  ON r.case_id = c.id
                  JOIN latest_llm l  ON l.case_id = c.id
                 WHERE r.resolved_at >= :from AND r.resolved_at < :to
                   AND l.recommendation IN (:recommendApprove, :recommendReject)
                   AND s.name IN (:approved, :rejected)"""
                + filters(filter);
        MapSqlParameterSource params = period(from, to, filter)
                .addValue("recommendApprove", Classification.LLM_RECOMIENDA_APROBAR.name())
                .addValue("recommendReject", Classification.LLM_NO_RECOMIENDA_APROBAR.name())
                .addValue("approved", CaseStatus.APPROVED.name())
                .addValue("rejected", CaseStatus.REJECTED.name());
        return query(template -> template.queryForObject(sql, params, (rs, rowNum) ->
                RecommendationAgreement.of(rs.getLong("decided"), rs.getLong("agreed"))));
    }

    /** Ordered by catalog id (the lifecycle), not size, so the chart reads as the claim advances. */
    @Transactional(readOnly = true)
    public List<MetricCount> countByStatus(Instant from, Instant to, MetricsFilter filter) {
        String sql = "SELECT s.name AS label, count(*) AS total"
                + FROM_CASES + "\n  JOIN case_status s ON s.id = c.current_status_id"
                + REPORTED_WINDOW + filters(filter) + " GROUP BY s.id, s.name ORDER BY s.id";
        return query(template -> template.query(sql, period(from, to, filter), COUNT_ROW));
    }

    @Transactional(readOnly = true)
    public List<MetricCount> countByBranch(Instant from, Instant to, MetricsFilter filter) {
        String sql = "SELECT b.name AS label, count(*) AS total"
                + FROM_CASES + "\n  JOIN branch b ON b.id = cc.branch_id"
                + REPORTED_WINDOW + filters(filter) + " GROUP BY b.name ORDER BY total DESC, label";
        return query(template -> template.query(sql, period(from, to, filter), COUNT_ROW));
    }

    /** Fast Track flag first, else the newest model run; a claim still classifying gives null. */
    @Transactional(readOnly = true)
    public List<MetricCount> countByClassification(Instant from, Instant to, MetricsFilter filter) {
        String sql = "WITH " + LATEST_LLM_CTE + """

                SELECT CASE WHEN c.was_fast_track THEN 'FAST_TRACK' ELSE l.recommendation END AS label,
                       count(*) AS total"""
                + FROM_CASES + "\n  LEFT JOIN latest_llm l ON l.case_id = c.id"
                + REPORTED_WINDOW + filters(filter) + " GROUP BY 1 ORDER BY total DESC, label";
        return query(template -> template.query(sql, period(from, to, filter), COUNT_ROW));
    }

    @Transactional(readOnly = true)
    public List<MetricCount> countByRiskBand(Instant from, Instant to, MetricsFilter filter) {
        String sql = "SELECT c.risk_band AS label, count(*) AS total" + FROM_CASES
                + REPORTED_WINDOW + filters(filter) + " GROUP BY c.risk_band ORDER BY total DESC, label";
        return query(template -> template.query(sql, period(from, to, filter), COUNT_ROW));
    }

    /** {@code waitingSeconds} is part of {@code totalSeconds}; both null when nothing was decided. */
    public record ResolutionSplit(Double totalSeconds, Double waitingSeconds) {

        public static final ResolutionSplit NONE = new ResolutionSplit(null, null);
    }

    /**
     * Waiting on third parties ({@link CaseResolutionSql#WAITING_CTE}) interrupts the legal term, so it
     * is not the operation's time. DECIDED cases only: a lapsed case is months of the insured's silence.
     */
    @Transactional(readOnly = true)
    public ResolutionSplit resolutionSplit(Instant from, Instant to, MetricsFilter filter) {
        String sql = CaseResolutionSql.RESOLUTION_CTE + ",\n" + CaseResolutionSql.WAITING_CTE + """

                SELECT avg(EXTRACT(EPOCH FROM (r.resolved_at - c.reported_at))) AS total_seconds,
                       avg(COALESCE(wt.waiting_seconds, 0)) AS waiting_seconds"""
                + FROM_CASES + """

                  JOIN case_status s ON s.id = c.current_status_id AND s.is_final
                  JOIN resolution r  ON r.case_id = c.id
                  LEFT JOIN waiting wt ON wt.case_id = c.id
                 WHERE r.resolved_at >= :from AND r.resolved_at < :to
                   AND s.name IN (:approved, :rejected)"""
                + filters(filter);
        MapSqlParameterSource params = period(from, to, filter)
                .addValue("approved", CaseStatus.APPROVED.name())
                .addValue("rejected", CaseStatus.REJECTED.name())
                .addValue("pausing", CaseStatus.pausingTheTerm().stream().map(Enum::name).toList());
        return query(template -> template.queryForObject(sql, params, (rs, rowNum) -> {
            double total = rs.getDouble("total_seconds");
            if (rs.wasNull()) {
                return ResolutionSplit.NONE;
            }
            double waiting = rs.getDouble("waiting_seconds");
            return new ResolutionSplit(total, rs.wasNull() ? 0 : waiting);
        }));
    }

    /**
     * Decided cases whose handling time (total minus waiting) exceeded the target, so requesting an
     * expert report doesn't count against it. Lapsed excluded; closing on the target day meets it.
     */
    @Transactional(readOnly = true)
    public long countDecidedOverTarget(Instant from, Instant to, int targetDays, MetricsFilter filter) {
        String sql = CaseResolutionSql.RESOLUTION_CTE + ",\n" + CaseResolutionSql.WAITING_CTE + """

                SELECT count(*) AS total"""
                + FROM_CASES + """

                  JOIN case_status s ON s.id = c.current_status_id AND s.is_final
                  JOIN resolution r  ON r.case_id = c.id
                  LEFT JOIN waiting wt ON wt.case_id = c.id
                 WHERE r.resolved_at >= :from AND r.resolved_at < :to
                   AND s.name IN (:approved, :rejected)
                   AND (r.resolved_at - c.reported_at)
                       - make_interval(secs => COALESCE(wt.waiting_seconds, 0))
                       > make_interval(days => :targetDays)"""
                + filters(filter);
        MapSqlParameterSource params = period(from, to, filter)
                .addValue("approved", CaseStatus.APPROVED.name())
                .addValue("rejected", CaseStatus.REJECTED.name())
                .addValue("pausing", CaseStatus.pausingTheTerm().stream().map(Enum::name).toList())
                .addValue("targetDays", targetDays);
        Long total = query(template -> template.queryForObject(sql, params, Long.class));
        return total == null ? 0 : total;
    }

    /**
     * Art. 56 compliance over cases DECIDED in the period. The deadline is read from the case, not
     * recomputed: cases-service owns that rule. Compared by day: the term ends with its last day.
     */
    @Transactional(readOnly = true)
    public LegalDeadline legalDeadlineCompliance(
            Instant from, Instant to, ZoneId zone, MetricsFilter filter) {
        String sql = CaseResolutionSql.RESOLUTION_CTE + """

                SELECT count(*) AS decided,
                       count(*) FILTER (
                           WHERE (r.resolved_at AT TIME ZONE :zone)::date <= c.response_deadline
                       ) AS on_time"""
                + FROM_CASES + """

                  JOIN case_status s ON s.id = c.current_status_id AND s.is_final
                  JOIN resolution r  ON r.case_id = c.id
                 WHERE r.resolved_at >= :from AND r.resolved_at < :to
                   AND s.name IN (:approved, :rejected)"""
                + filters(filter);
        MapSqlParameterSource params = period(from, to, filter)
                .addValue("approved", CaseStatus.APPROVED.name())
                .addValue("rejected", CaseStatus.REJECTED.name())
                .addValue("zone", zone.getId());
        return query(template -> template.queryForObject(sql, params, (rs, rowNum) ->
                LegalDeadline.of(rs.getLong("decided"), rs.getLong("on_time"))));
    }

    /** Counts reopened cases ({@code EXISTS}), not reopenings. */
    @Transactional(readOnly = true)
    public ReopeningRate reopeningRate(Instant from, Instant to, MetricsFilter filter) {
        String sql = CaseResolutionSql.RESOLUTION_CTE + """

                SELECT count(*) AS resolved,
                       count(*) FILTER (WHERE EXISTS (
                           SELECT 1
                             FROM case_status_history h
                             JOIN case_status hs ON hs.id = h.initial_status_id AND hs.is_final
                             JOIN case_status hf ON hf.id = h.final_status_id AND NOT hf.is_final
                            WHERE h.case_id = c.id
                       )) AS reopened"""
                + FROM_CASES + """

                  JOIN case_status s ON s.id = c.current_status_id AND s.is_final
                  JOIN resolution r  ON r.case_id = c.id
                 WHERE r.resolved_at >= :from AND r.resolved_at < :to"""
                + filters(filter);
        return query(template -> template.queryForObject(sql, period(from, to, filter),
                (rs, rowNum) -> ReopeningRate.of(rs.getLong("resolved"), rs.getLong("reopened"))));
    }

    /**
     * Only {@code AUTHORIZED} settlements, by {@code confirmed_at}. Deductions are read frozen from the
     * settlement: the branch's deductible may have changed since.
     */
    @Transactional(readOnly = true)
    public SettledAmounts settledAmounts(Instant from, Instant to, MetricsFilter filter) {
        String sql = """
                SELECT count(*) AS settlements,
                       COALESCE(sum(st.settled_amount), 0) AS settled,
                       COALESCE(sum(c.claimed_amount), 0) AS claimed,
                       count(*) FILTER (WHERE c.claimed_amount IS NOT NULL) AS claimed_cases,
                       COALESCE(sum(st.deductible_amount), 0) AS deductible,
                       COALESCE(sum(st.pending_installments_amount), 0) AS installments,
                       COALESCE(sum(st.overdue_balance_amount), 0) AS overdue"""
                + FROM_CASES + """

                  JOIN case_settlement st ON st.case_id = c.id
                 WHERE st.confirmed_at >= :from AND st.confirmed_at < :to
                   AND st.status = :authorized"""
                + filters(filter);
        MapSqlParameterSource params = period(from, to, filter).addValue("authorized", SettlementStatus.AUTHORIZED.name());
        return query(template -> template.queryForObject(sql, params, (rs, rowNum) ->
                SettledAmounts.of(
                        rs.getLong("settlements"),
                        rs.getBigDecimal("settled"),
                        rs.getBigDecimal("claimed"),
                        rs.getLong("claimed_cases"),
                        rs.getBigDecimal("deductible"),
                        rs.getBigDecimal("installments"),
                        rs.getBigDecimal("overdue"))));
    }

    /**
     * Only rejected cases count as saved: one approved despite fraud saved nothing. Expert backing uses
     * {@code EXISTS}, since a join over several derivations would double-count.
     */
    @Transactional(readOnly = true)
    public FraudDetection fraudDetection(Instant from, Instant to, MetricsFilter filter) {
        String sql = CaseResolutionSql.RESOLUTION_CTE + """

                SELECT count(*) AS decided,
                       count(*) FILTER (WHERE c.fraud_determined) AS fraud_determined,
                       count(*) FILTER (WHERE c.fraud_determined AND EXISTS (
                           SELECT 1 FROM expert_assessment ea
                            WHERE ea.case_id = c.id AND ea.verdict = :fraudConfirmed
                       )) AS backed_by_expert,
                       COALESCE(sum(c.claimed_amount) FILTER (
                           WHERE c.fraud_determined AND s.name = :rejected
                       ), 0) AS amount_not_paid"""
                + FROM_CASES + """

                  JOIN case_status s ON s.id = c.current_status_id AND s.is_final
                  JOIN resolution r  ON r.case_id = c.id
                 WHERE r.resolved_at >= :from AND r.resolved_at < :to
                   AND s.name IN (:approved, :rejected)"""
                + filters(filter);
        MapSqlParameterSource params = period(from, to, filter)
                .addValue("approved", CaseStatus.APPROVED.name())
                .addValue("rejected", CaseStatus.REJECTED.name())
                .addValue("fraudConfirmed", ExpertVerdict.FRAUD_CONFIRMED.name());
        return query(template -> template.queryForObject(sql, params, (rs, rowNum) -> new FraudDetection(
                rs.getLong("decided"),
                rs.getLong("fraud_determined"),
                rs.getLong("backed_by_expert"),
                rs.getBigDecimal("amount_not_paid"))));
    }

    @Transactional(readOnly = true)
    public FastTrackImpact fastTrackImpact(Instant from, Instant to, MetricsFilter filter) {
        String sql = CaseResolutionSql.RESOLUTION_CTE + """

                SELECT count(*) FILTER (WHERE c.was_fast_track) AS fast_track_decided,
                       avg(EXTRACT(EPOCH FROM (r.resolved_at - c.reported_at)))
                           FILTER (WHERE c.was_fast_track) AS fast_track_seconds,
                       count(*) FILTER (WHERE NOT c.was_fast_track) AS standard_decided,
                       avg(EXTRACT(EPOCH FROM (r.resolved_at - c.reported_at)))
                           FILTER (WHERE NOT c.was_fast_track) AS standard_seconds"""
                + FROM_CASES + """

                  JOIN case_status s ON s.id = c.current_status_id AND s.is_final
                  JOIN resolution r  ON r.case_id = c.id
                 WHERE r.resolved_at >= :from AND r.resolved_at < :to
                   AND s.name IN (:approved, :rejected)"""
                + filters(filter);
        MapSqlParameterSource params = period(from, to, filter)
                .addValue("approved", CaseStatus.APPROVED.name())
                .addValue("rejected", CaseStatus.REJECTED.name());
        return query(template -> template.queryForObject(sql, params, (rs, rowNum) -> {
            // wasNull() refers to the last column read, so each average is checked as soon as it is read.
            long fastTrackDecided = rs.getLong("fast_track_decided");
            Double fastTrackHours = hours(rs, "fast_track_seconds");
            long standardDecided = rs.getLong("standard_decided");
            return new FastTrackImpact(fastTrackDecided, fastTrackHours, standardDecided,
                    hours(rs, "standard_seconds"));
        }));
    }

    /**
     * Derivations sent in the period per kind of third party. Only answered ones are averaged: pending
     * ones measured against now would shift on every refresh.
     */
    @Transactional(readOnly = true)
    public List<DerivationTurnaround> derivationTurnaround(Instant from, Instant to, MetricsFilter filter) {
        String sql = """
                SELECT ea.provider_type AS provider_type,
                       count(*) AS derived,
                       count(ea.report_received_at) AS answered,
                       avg(EXTRACT(EPOCH FROM (ea.report_received_at - ea.derived_at))) AS average_seconds"""
                + FROM_CASES + """

                  JOIN expert_assessment ea ON ea.case_id = c.id
                 WHERE ea.derived_at >= :from AND ea.derived_at < :to"""
                + filters(filter) + " GROUP BY ea.provider_type ORDER BY derived DESC, provider_type";
        return query(template -> template.query(sql, period(from, to, filter), (rs, rowNum) ->
                new DerivationTurnaround(
                        rs.getString("provider_type"),
                        rs.getLong("derived"),
                        rs.getLong("answered"),
                        hours(rs, "average_seconds"))));
    }

    /**
     * {@code FAIL} rows only, no advisory checks, one per case ({@code rule_result} is append-only).
     * Coverage and Fast Track checks have no {@code rule_id}, so they are named by type.
     */
    @Transactional(readOnly = true)
    public List<MetricCount> countByBlockingRule(Instant from, Instant to, MetricsFilter filter) {
        String sql = """
                SELECT COALESCE(ir.name, rr.rule_type) AS label,
                       count(DISTINCT rr.case_id) AS total"""
                + FROM_CASES + """

                  JOIN rule_result rr ON rr.case_id = c.id AND rr.result = :failed
                                     AND rr.rule_type NOT IN (:advisory)
                  LEFT JOIN insurer_rule ir ON ir.id = rr.rule_id"""
                + REPORTED_WINDOW + filters(filter)
                + " GROUP BY 1 ORDER BY total DESC, label";
        List<String> advisory = RuleType.advisoryRules().stream().map(RuleType::name).toList();
        return query(template -> template.query(sql,
                period(from, to, filter).addValue("failed", FAILED).addValue("advisory", advisory),
                COUNT_ROW));
    }

    @Transactional(readOnly = true)
    public List<ResolvedTotals> resolvedTotals(Instant from, Instant to, MetricsFilter filter) {
        String sql = CaseResolutionSql.RESOLUTION_CTE + """

                SELECT s.name AS status,
                       count(*) AS total,
                       avg(EXTRACT(EPOCH FROM (r.resolved_at - c.reported_at))) AS average_seconds"""
                + FROM_CASES + """

                  JOIN case_status s ON s.id = c.current_status_id AND s.is_final
                  JOIN resolution r  ON r.case_id = c.id
                 WHERE r.resolved_at >= :from AND r.resolved_at < :to"""
                + filters(filter) + " GROUP BY s.name";
        return query(template -> template.query(sql, period(from, to, filter), (rs, rowNum) -> {
            double average = rs.getDouble("average_seconds");
            return new ResolvedTotals(rs.getString("status"), rs.getLong("total"),
                    rs.wasNull() ? null : average);
        }));
    }

    /**
     * Buckets truncated in the insurer's time zone, not UTC: a claim filed at 9 PM in Buenos Aires
     * belongs to that day. Empty buckets are absent; the service fills the gaps.
     */
    @Transactional(readOnly = true)
    public List<TimelinePoint> timeline(
            Instant from, Instant to, TimelineGranularity granularity, ZoneId zone, MetricsFilter filter) {
        String sql = CaseResolutionSql.RESOLUTION_CTE + """
                ,
                reported AS (
                    SELECT date_trunc(:granularity, c.reported_at AT TIME ZONE :zone) AS bucket,
                           count(*) AS total"""
                + FROM_CASES + """

                     WHERE c.reported_at >= :from AND c.reported_at < :to"""
                + filters(filter) + """

                     GROUP BY 1
                ),
                resolved AS (
                    SELECT date_trunc(:granularity, r.resolved_at AT TIME ZONE :zone) AS bucket,
                           count(*) AS total"""
                + FROM_CASES + """

                       JOIN resolution r ON r.case_id = c.id
                     WHERE r.resolved_at >= :from AND r.resolved_at < :to"""
                + filters(filter) + """

                     GROUP BY 1
                )
                SELECT COALESCE(rep.bucket, res.bucket) AS bucket,
                       COALESCE(rep.total, 0) AS reported,
                       COALESCE(res.total, 0) AS resolved
                  FROM reported rep
                  FULL JOIN resolved res ON res.bucket = rep.bucket
                 ORDER BY 1""";
        MapSqlParameterSource params = period(from, to, filter)
                .addValue("granularity", granularity.datePart())
                .addValue("zone", zone.getId());
        return query(template -> template.query(sql, params, (rs, rowNum) -> new TimelinePoint(
                rs.getObject("bucket", LocalDate.class),
                rs.getLong("reported"),
                rs.getLong("resolved"))));
    }

    /**
     * Appended rather than {@code :branchId IS NULL OR ...}: Postgres can't infer the type of a
     * parameter only ever compared to NULL.
     */
    private static String filters(MetricsFilter filter) {
        StringBuilder sql = new StringBuilder();
        if (filter.branchId() != null) {
            sql.append("\n   AND cc.branch_id = :branchId");
        }
        if (filter.analystId() != null) {
            sql.append("\n   AND c.analyst_id = :analystId");
        }
        return sql.toString();
    }

    private static MapSqlParameterSource period(Instant from, Instant to, MetricsFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .addValue("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
        if (filter.branchId() != null) {
            params.addValue("branchId", filter.branchId());
        }
        if (filter.analystId() != null) {
            params.addValue("analystId", filter.analystId());
        }
        return params;
    }

    /** Keeps {@code avg}'s NULL as null, so a period with nothing to average doesn't read as 0 h. */
    private static Double hours(ResultSet rs, String column) throws SQLException {
        double seconds = rs.getDouble(column);
        return rs.wasNull() ? null : seconds / 3600;
    }

    private <T> T query(Function<NamedParameterJdbcTemplate, T> work) {
        // suppressClose: the connection is Hibernate's and Hibernate closes it.
        return entityManager.unwrap(Session.class).doReturningWork(connection ->
                work.apply(new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection, true))));
    }
}
