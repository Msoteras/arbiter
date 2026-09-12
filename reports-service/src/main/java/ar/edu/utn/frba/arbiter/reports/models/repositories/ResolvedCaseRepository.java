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
 * Read side of the resolution report: the cases that reached a final status, with the
 * classification and the analyst's decision that closed them.
 *
 * <p>It reads tables other modules own — {@code cases} and {@code case_status_history}
 * (cases-service), {@code llm_analysis} and {@code case_classification} (classification-service).
 * That is what the architecture document draws for this module: Reportes connects straight to
 * PostgreSQL, with no REST link to Expedientes (§3, and §10: "La base de datos es compartida entre
 * todos ellos"). It is also the same documented exception as cases-service's
 * {@code CaseAnalysisRepository}: every one of those tables lives in the same tenant schema, so a
 * report over a whole period is one query instead of one HTTP call per case. Read-only, plain JDBC
 * over a named set of columns and no entities, so this module never claims ownership of them.
 *
 * <p>The query runs on Hibernate's connection, not on one from the pool: the table names are
 * unqualified and resolve through the {@code search_path} that {@code TenantConnectionProvider}
 * sets, which it only does on the connections Hibernate asks for (see the Javadoc of cases-service's
 * {@code CaseAnalysisRepository} for the bug that caused over there).
 */
@Repository
@RequiredArgsConstructor
public class ResolvedCaseRepository {

    // "Resolved" means the case sits in a final status of the platform catalog (is_final, not a
    // hardcoded list), and the resolution date is the LAST transition into that status: a case that
    // was reopened and closed again counts once, on the day it closed for good. The latest
    // transition is picked before filtering by period on purpose — filtering first would pick up an
    // earlier closing that a reopen already undid.
    //
    // The analyst is whoever decided; a LAPSED case has no decision, so it falls back to whoever
    // owned the case when it lapsed.
    private static final String RESOLVED_CASES = """
            WITH resolution AS (
                SELECT DISTINCT ON (h.case_id) h.case_id, h.changed_at AS resolved_at
                  FROM case_status_history h
                  JOIN cases c ON c.id = h.case_id AND h.final_status_id = c.current_status_id
                 ORDER BY h.case_id, h.changed_at DESC, h.id DESC
            ),
            latest_llm AS (
                SELECT DISTINCT ON (case_id) case_id, recommendation
                  FROM llm_analysis
                 ORDER BY case_id, id DESC
            )
            SELECT c.id, c.reported_at, c.was_fast_track, r.resolved_at,
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
              LEFT JOIN latest_llm l          ON l.case_id = c.id
              LEFT JOIN case_classification k ON k.id = c.classification_id
              LEFT JOIN claims_analyst a      ON a.id = COALESCE(k.analyst_id, c.analyst_id)
             WHERE r.resolved_at >= :from AND r.resolved_at < :to
            """;

    private final EntityManager entityManager;

    /**
     * @param from       inclusive
     * @param to         exclusive
     * @param claimCause claim cause name, matched across branches (the same "Hurto" exists in each
     *                   one, same as the inbox's filter); null for every cause
     */
    @Transactional(readOnly = true)
    public List<ResolutionReportRow> findResolvedBetween(Instant from, Instant to, String claimCause) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .addValue("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
        StringBuilder sql = new StringBuilder(RESOLVED_CASES);
        // Appended rather than `:claimCause IS NULL OR ...`: Postgres can't infer the type of a
        // parameter that is only ever compared to NULL and rejects the statement.
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
                classification(rs.getBoolean("was_fast_track"), rs.getString("recommendation")),
                rs.getString("decision"),
                CaseStatus.valueOf(rs.getString("final_status")),
                fullName(rs.getString("analyst_name"), rs.getString("analyst_surname")));
    }

    /**
     * A Fast Track leaves no {@code llm_analysis} row — the model never ran — so the flag on the
     * case is the only trace of it, and it wins over any older model run.
     */
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
