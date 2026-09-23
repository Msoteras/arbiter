package ar.edu.utn.frba.arbiter.reports.models.repositories;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Read side of the resolution report.
 *
 * <p>Reads tables owned by other modules ({@code cases}, {@code case_status_history},
 * {@code llm_analysis}, {@code case_classification}) directly, as the architecture document draws
 * this module: all live in the same tenant schema, so a whole-period report is one query instead of
 * one HTTP call per case. Read-only plain JDBC over named columns, no entities, so this module never
 * claims ownership of those tables.
 *
 * <p>The query runs on Hibernate's connection, not one from the pool: the table names are unqualified
 * and resolve through the {@code search_path} that {@code TenantConnectionProvider} only sets on
 * connections Hibernate asks for.
 */
@Repository
@RequiredArgsConstructor
public class ResolvedCaseRepository {

    /** The analyst is whoever decided; a LAPSED case has no decision, so it falls back to its owner. */
    private static final String RESOLVED_CASES = CaseResolutionSql.RESOLUTION_CTE + ",\n"
            + CaseResolutionSql.WAITING_CTE + """
            ,
            latest_llm AS (
                SELECT DISTINCT ON (case_id) case_id, recommendation
                  FROM llm_analysis
                 ORDER BY case_id, id DESC
            )
            SELECT c.id, c.reported_at, c.was_fast_track, r.resolved_at,
                   COALESCE(wt.waiting_seconds, 0) AS waiting_seconds,
                   i.name AS insured_name, i.surname AS insured_surname, i.dni,
                   b.name AS branch, cc.name AS claim_cause, s.name AS final_status,
                   l.recommendation, k.decision,
                   a.name AS analyst_name, a.surname AS analyst_surname
              FROM cases c
              JOIN case_status s  ON s.id = c.current_status_id AND s.is_final
              JOIN resolution r   ON r.case_id = c.id
              JOIN insured i      ON i.id = c.insured_id
              JOIN claim_cause cc ON cc.id = c.claim_cause_id
              JOIN branch b       ON b.id = cc.branch_id
              LEFT JOIN waiting wt            ON wt.case_id = c.id
              LEFT JOIN latest_llm l          ON l.case_id = c.id
              LEFT JOIN case_classification k ON k.id = c.classification_id
              LEFT JOIN claims_analyst a      ON a.id = COALESCE(k.analyst_id, c.analyst_id)
             WHERE r.resolved_at >= :from AND r.resolved_at < :to
            """;

    private final EntityManager entityManager;

    /**
     * @param from       inclusive
     * @param to         exclusive
     * @param claimCause matched by name across branches (each branch has its own "Hurto"); combined
     *                   with {@code branchId} it narrows to that branch's cause
     */
    @Transactional(readOnly = true)
    public List<ResolutionReportRow> findResolvedBetween(Instant from, Instant to, Long branchId,
                                                         String claimCause) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .addValue("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .addValue("pausing", CaseStatus.pausingTheTerm().stream().map(Enum::name).toList());
        StringBuilder sql = new StringBuilder(RESOLVED_CASES);
        // Appended rather than `:claimCause IS NULL OR ...`: Postgres can't infer the type of a
        // parameter that is only ever compared to NULL and rejects the statement.
        if (branchId != null) {
            sql.append("   AND cc.branch_id = :branchId\n");
            params.addValue("branchId", branchId);
        }
        if (claimCause != null) {
            sql.append("   AND cc.name = :claimCause\n");
            params.addValue("claimCause", claimCause);
        }
        sql.append(" ORDER BY r.resolved_at, c.id");

        // suppressClose: the connection is Hibernate's and Hibernate closes it.
        return entityManager.unwrap(Session.class).doReturningWork(connection ->
                new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection, true))
                        .query(sql.toString(), params, (rs, rowNum) -> toRow(rs)));
    }

    /**
     * Lets the report name its branch filter even when it matched nothing.
     *
     * @return null if no branch has that id
     */
    @Transactional(readOnly = true)
    public String findBranchName(Long branchId) {
        return entityManager.unwrap(Session.class).doReturningWork(connection ->
                new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection, true))
                        .query("SELECT name FROM branch WHERE id = :branchId",
                                new MapSqlParameterSource("branchId", branchId),
                                rs -> rs.next() ? rs.getString("name") : null));
    }

    private static ResolutionReportRow toRow(ResultSet rs) throws SQLException {
        Instant reportedAt = rs.getObject("reported_at", OffsetDateTime.class).toInstant();
        Instant resolvedAt = rs.getObject("resolved_at", OffsetDateTime.class).toInstant();
        return new ResolutionReportRow(
                rs.getLong("id"),
                fullName(rs.getString("insured_name"), rs.getString("insured_surname")),
                rs.getString("dni"),
                rs.getString("branch"),
                rs.getString("claim_cause"),
                reportedAt,
                resolvedAt,
                Duration.between(reportedAt, resolvedAt).toMinutes(),
                Math.round(rs.getDouble("waiting_seconds")) / 60,
                classification(rs.getBoolean("was_fast_track"), rs.getString("recommendation")),
                rs.getString("decision"),
                CaseStatus.valueOf(rs.getString("final_status")),
                fullName(rs.getString("analyst_name"), rs.getString("analyst_surname")));
    }

    /** A Fast Track leaves no {@code llm_analysis} row, so the case flag wins over any older model run. */
    private static Classification classification(boolean wasFastTrack, String recommendation) {
        if (wasFastTrack) {
            return Classification.FAST_TRACK;
        }
        return recommendation == null ? null : Classification.valueOf(recommendation);
    }

    private static String fullName(String name, String surname) {
        return name == null ? null : name + " " + surname;
    }
}
