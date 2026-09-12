package ar.edu.utn.frba.arbiter.reports.models.repositories;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.reports.dto.IntakeFunnel;
import ar.edu.utn.frba.arbiter.reports.dto.MetricCount;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsFilter;
import ar.edu.utn.frba.arbiter.reports.dto.RecommendationAgreement;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;

/**
 * The aggregations behind the referent's dashboard. Every figure is counted by the database and
 * comes back as a handful of rows: a dashboard over a quarter would otherwise drag every case of
 * the quarter into memory just to count it here.
 *
 * <p>Like {@code ResolvedCaseRepository}, it reads tables other modules own ({@code cases} and
 * {@code case_status_history} from cases-service, {@code llm_analysis} from
 * classification-service). The architecture document connects Reportes straight to PostgreSQL
 * rather than through REST (section 3, and section 10: the database is shared between all of
 * them), and every one of those tables lives in the same tenant schema. Read-only, plain JDBC over
 * a named set of columns and no entities, so this module never claims ownership of them.
 *
 * <p><b>The queries run on Hibernate's connection, not on one from the pool.</b> The table names
 * are unqualified and resolve through the {@code search_path} that {@code TenantConnectionProvider}
 * sets, and it only sets it on the connections Hibernate asks for. A template built over the
 * {@code DataSource} would borrow straight from the pool, with no search_path, and fail with
 * "relation cases does not exist" against the real multi-tenant database — invisible in tests,
 * where the schema is flat and everything lands in {@code public}.
 */
@Repository
@RequiredArgsConstructor
public class ClaimMetricsRepository {

    /**
     * When a case closed, defined exactly as {@code ResolvedCaseRepository} defines it so that the
     * dashboard and the resolution report never disagree about the same period: a case is resolved
     * when it sits in a final status of the platform catalog ({@code is_final}, not a hardcoded
     * list), and the resolution date is the LAST transition into that status — a case that was
     * reopened and closed again counts once, on the day it closed for good.
     */
    private static final String RESOLUTION_CTE = """
            WITH resolution AS (
                SELECT DISTINCT ON (h.case_id) h.case_id, h.changed_at AS resolved_at
                  FROM case_status_history h
                  JOIN cases c ON c.id = h.case_id AND h.final_status_id = c.current_status_id
                 ORDER BY h.case_id, h.changed_at DESC, h.id DESC
            )""";

    /** The newest model run per case — the analyses are append-only, one row per run. */
    private static final String LATEST_LLM_CTE = """
            latest_llm AS (
                SELECT DISTINCT ON (case_id) case_id, recommendation
                  FROM llm_analysis
                 ORDER BY case_id, id DESC
            )""";

    /**
     * Claim cause is joined everywhere, not only where its name is read: the branch filter hangs
     * off it, and one uniform join beats a query shape that changes depending on the filter.
     */
    // The leading newline is explicit, not decoration: a text block strips its own indentation, so
    // without it this would weld itself onto whatever SELECT it is concatenated after.
    private static final String FROM_CASES = "\n" + """
              FROM cases c
              JOIN claim_cause cc ON cc.id = c.claim_cause_id""";

    /** Claims FILED in the period — the population every distribution below is drawn from. */
    private static final String REPORTED_WINDOW = " WHERE c.reported_at >= :from AND c.reported_at < :to";

    private static final RowMapper<MetricCount> COUNT_ROW =
            (rs, rowNum) -> new MetricCount(rs.getString("label"), rs.getLong("total"));

    private final EntityManager entityManager;

    /**
     * How many claims came in, and how many of them the rules engine let through on Fast Track.
     *
     * @param fastTrack claims carrying the Fast Track flag. The flag on the case is the only trace
     *                  of it: a Fast Track never runs the model, so it leaves no analysis row.
     */
    public record IntakeTotals(long reported, long fastTrack) {}

    /**
     * One final status and how the claims that ended there behaved.
     *
     * @param averageSeconds from filing to resolution, averaged over this status alone; null when
     *                       the average is not computable
     */
    public record ResolvedTotals(String status, long count, Double averageSeconds) {}

    @Transactional(readOnly = true)
    public IntakeTotals intakeTotals(Instant from, Instant to, MetricsFilter filter) {
        String sql = "SELECT count(*) AS reported, count(*) FILTER (WHERE c.was_fast_track) AS fast_track"
                + FROM_CASES + REPORTED_WINDOW + filters(filter);
        return query(template -> template.queryForObject(sql, period(from, to, filter),
                (rs, rowNum) -> new IntakeTotals(rs.getLong("reported"), rs.getLong("fast_track"))));
    }

    /**
     * The period's intake followed forward. One pass over the same cohort, five counters: splitting
     * it into five queries would scan the same rows five times to draw one strip of the screen.
     */
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
     * How often the analyst landed where the model pointed, over the claims resolved in the period
     * — the same population the summary's rates are drawn from, on purpose, so the two panels can
     * be read side by side.
     *
     * <p><b>Agreement is measured against the final status, not against the decision string.</b>
     * That column holds "APPROVE" for decisions the app recorded and "APROBAR" for the ones that
     * came from the seed data, because classification-service normalizes to English on write and
     * the seed predates it. The status is enum-backed and has no such split.
     */
    @Transactional(readOnly = true)
    public RecommendationAgreement recommendationAgreement(Instant from, Instant to, MetricsFilter filter) {
        String sql = RESOLUTION_CTE + ",\n" + LATEST_LLM_CTE + """

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

    /**
     * The claims filed in the period, by the status they sit in now. Ordered by the lifecycle (the
     * catalog's own ids) and not by size, so the bar chart reads left to right as the claim
     * advances.
     */
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

    /**
     * By classification, with the same precedence the resolution report uses: Fast Track is decided
     * by the rules engine and the model never runs, so the flag wins over any older run; otherwise
     * it is the newest model run. A claim still being classified contributes a null label.
     */
    @Transactional(readOnly = true)
    public List<MetricCount> countByClassification(Instant from, Instant to, MetricsFilter filter) {
        String sql = "WITH " + LATEST_LLM_CTE + """

                SELECT CASE WHEN c.was_fast_track THEN 'FAST_TRACK' ELSE l.recommendation END AS label,
                       count(*) AS total"""
                + FROM_CASES + "\n  LEFT JOIN latest_llm l ON l.case_id = c.id"
                + REPORTED_WINDOW + filters(filter) + " GROUP BY 1 ORDER BY total DESC, label";
        return query(template -> template.query(sql, period(from, to, filter), COUNT_ROW));
    }

    /**
     * By risk band, read off the denormalized column on the case — the same copy the analyst's
     * inbox filters by. A claim the scoring never reached contributes a null label.
     */
    @Transactional(readOnly = true)
    public List<MetricCount> countByRiskBand(Instant from, Instant to, MetricsFilter filter) {
        String sql = "SELECT c.risk_band AS label, count(*) AS total" + FROM_CASES
                + REPORTED_WINDOW + filters(filter) + " GROUP BY c.risk_band ORDER BY total DESC, label";
        return query(template -> template.query(sql, period(from, to, filter), COUNT_ROW));
    }

    /**
     * El tiempo de resolución partido en dos: cuánto tardó en total y cuánto de eso fue esperando a
     * alguien de afuera de la compañía.
     *
     * @param totalSeconds  de la denuncia a la decisión, promedio. Es el tiempo que vivió el
     *                      asegurado, y por eso sigue siendo el número principal.
     * @param waitingSeconds la parte de ese tiempo en la que el expediente estuvo esperando
     *                      documentación del asegurado, el informe de un perito o el equipo del
     *                      servicio técnico, promedio. Null junto con el total cuando no hubo
     *                      decisiones en el período.
     */
    public record ResolutionSplit(Double totalSeconds, Double waitingSeconds) {

        public static final ResolutionSplit NONE = new ResolutionSplit(null, null);
    }

    /**
     * Promedio del tiempo total y de la parte que no le corre a la compañía.
     *
     * <p>El procedimiento de la aseguradora es explícito: pedir documentación adicional o derivar a
     * un perito o a un servicio técnico <b>interrumpe</b> el plazo legal para expedirse. Medir sólo
     * el reloj de pared le carga a la gestión semanas que ni la ley ni el procedimiento le imputan,
     * y deja un número que el referente no puede accionar: no sabe cuánto de eso es suyo.
     *
     * <p>Cada tramo del historial se recorta contra la ventana del expediente ({@code GREATEST} /
     * {@code LEAST}): una espera que arrancó antes de la denuncia o que seguía abierta al cerrarse
     * el caso cuenta sólo por la parte que cae adentro. El {@code GREATEST(..., 0)} descarta los
     * tramos que quedan enteros afuera, que restarían.
     *
     * <p>Sobre los DECIDIDOS, igual que el promedio del resumen: un caducado son 18 meses de
     * silencio del asegurado y arruinaría las dos mitades a la vez.
     */
    @Transactional(readOnly = true)
    public ResolutionSplit resolutionSplit(Instant from, Instant to, MetricsFilter filter) {
        String sql = RESOLUTION_CTE + """
                ,
                ordered AS (
                    SELECT h.case_id,
                           h.changed_at AS from_at,
                           LEAD(h.changed_at) OVER (
                               PARTITION BY h.case_id ORDER BY h.changed_at, h.id) AS to_at,
                           st.name AS status
                      FROM case_status_history h
                      JOIN case_status st ON st.id = h.final_status_id
                ),
                waiting AS (
                    SELECT o.case_id,
                           SUM(GREATEST(EXTRACT(EPOCH FROM (
                               LEAST(COALESCE(o.to_at, r.resolved_at), r.resolved_at)
                               - GREATEST(o.from_at, w.reported_at))), 0)) AS waiting_seconds
                      FROM ordered o
                      JOIN resolution r ON r.case_id = o.case_id
                      JOIN cases w      ON w.id = o.case_id
                     WHERE o.status IN (:pausing)
                     GROUP BY o.case_id
                )
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
     * Cuántos de los expedientes DECIDIDOS en el período tardaron más que el objetivo que fijó la
     * aseguradora.
     *
     * <p>Decididos y no resueltos, igual que el promedio: un caducado cerró sin que nadie lo
     * decidiera, y contarlo como "fuera de objetivo" mediría los 18 meses de silencio del asegurado
     * y no la operación de la compañía.
     *
     * <p>La comparación es estricta: un expediente que cerró justo en el día del objetivo lo
     * cumplió, no lo excedió.
     */
    @Transactional(readOnly = true)
    public long countDecidedOverTarget(Instant from, Instant to, int targetDays, MetricsFilter filter) {
        String sql = RESOLUTION_CTE + """

                SELECT count(*) AS total"""
                + FROM_CASES + """

                  JOIN case_status s ON s.id = c.current_status_id AND s.is_final
                  JOIN resolution r  ON r.case_id = c.id
                 WHERE r.resolved_at >= :from AND r.resolved_at < :to
                   AND s.name IN (:approved, :rejected)
                   AND r.resolved_at - c.reported_at > make_interval(days => :targetDays)"""
                + filters(filter);
        MapSqlParameterSource params = period(from, to, filter)
                .addValue("approved", CaseStatus.APPROVED.name())
                .addValue("rejected", CaseStatus.REJECTED.name())
                .addValue("targetDays", targetDays);
        Long total = query(template -> template.queryForObject(sql, params, Long.class));
        return total == null ? 0 : total;
    }

    /** One row per final status reached in the period, with its own average time to get there. */
    @Transactional(readOnly = true)
    public List<ResolvedTotals> resolvedTotals(Instant from, Instant to, MetricsFilter filter) {
        String sql = RESOLUTION_CTE + """

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
     * Claims filed and claims resolved, bucketed by calendar day, week or month. Buckets are
     * truncated in the insurer's time zone rather than UTC, so a claim filed at 9 PM in Buenos
     * Aires belongs to that day and not to the next one.
     *
     * <p>Buckets where neither series moved are simply absent — filling the gaps is the service's
     * job, which is the one that knows the whole period.
     */
    @Transactional(readOnly = true)
    public List<TimelinePoint> timeline(
            Instant from, Instant to, TimelineGranularity granularity, ZoneId zone, MetricsFilter filter) {
        String sql = RESOLUTION_CTE + """
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
     * The optional cuts, as extra {@code AND}s. They are appended rather than written as
     * {@code :branchId IS NULL OR ...} because Postgres can't infer the type of a parameter that is
     * only ever compared to NULL and rejects the statement — the same reason
     * {@code ResolvedCaseRepository} appends its claim-cause filter.
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

    /** See the class Javadoc: the connection has to be Hibernate's, or the search_path is wrong. */
    private <T> T query(Function<NamedParameterJdbcTemplate, T> work) {
        // suppressClose: the connection is Hibernate's and Hibernate closes it.
        return entityManager.unwrap(Session.class).doReturningWork(connection ->
                work.apply(new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection, true))));
    }
}
