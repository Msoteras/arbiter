package ar.edu.utn.frba.arbiter.reports.models.repositories;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.ExpertVerdict;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.FraudSignal;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Read side of the fraud report: the cases filed in a period that carry at least one fraud signal.
 *
 * <p>Like {@code ResolvedCaseRepository}, it reads tables other modules own — {@code cases},
 * {@code case_documents} (cases-service), {@code image_analysis} (classification-service). That is
 * what the architecture document draws for this module (§3: Reportes connects straight to
 * PostgreSQL with no REST link to Expedientes; §10: the database is shared between all of them),
 * and every one of those tables lives in the same tenant schema. Read-only, plain JDBC over a named
 * set of columns and no entities, so this module never claims ownership of them.
 *
 * <p>The query runs on Hibernate's connection and not on one from the pool: the table names are
 * unqualified and resolve through the {@code search_path} that {@code TenantConnectionProvider}
 * sets, which it only does on the connections Hibernate asks for (see {@code ResolvedCaseRepository}
 * for the bug that caused).
 */
@Repository
@RequiredArgsConstructor
public class FlaggedCaseRepository {

    /**
     * The insured's claims in the 12 months up to this one, and how many of the case's images the
     * forensic pass flagged. Correlated subqueries rather than joins: both are counts over a
     * different grain than the row, and joining them would multiply the case by its own documents.
     *
     * <p>The window is counted from each case's own {@code reported_at} and not from the period's
     * end, so a case reads the same whenever the report is run — a number that changes depending on
     * when you asked is not something you can put in front of an auditor.
     */
    private static final String FLAGGED_CASES = """
            WITH candidate AS (
                SELECT c.id, c.reported_at, c.risk_band, c.fraud_determined,
                       c.insured_id, c.claim_cause_id, c.current_status_id,
                       (SELECT count(*)
                          FROM cases prior
                         WHERE prior.insured_id = c.insured_id
                           AND prior.reported_at <= c.reported_at
                           AND prior.reported_at > c.reported_at - INTERVAL '12 months'
                       ) AS claims_in_window,
                       (SELECT count(*)
                          FROM image_analysis ia
                          JOIN case_documents d ON d.id = ia.case_document_id
                         WHERE d.case_id = c.id AND ia.is_suspicious
                       ) AS suspicious_images
                  FROM cases c
                 WHERE c.reported_at >= :from AND c.reported_at < :to
            )
            SELECT c.id, c.reported_at, c.risk_band, c.fraud_determined,
                   c.claims_in_window, c.suspicious_images,
                   i.name AS insured_name, i.surname AS insured_surname, i.dni,
                   b.name AS branch, cc.name AS claim_cause, s.name AS status,
                   EXISTS (SELECT 1 FROM expert_assessment ea
                            WHERE ea.case_id = c.id AND ea.verdict = :fraudConfirmed) AS expert_backed
              FROM candidate c
              JOIN claim_cause cc ON cc.id = c.claim_cause_id
              JOIN branch b       ON b.id = cc.branch_id
              JOIN case_status s  ON s.id = c.current_status_id
              JOIN insured i      ON i.id = c.insured_id
             WHERE (c.risk_band IN (:highBands)
                 OR c.claims_in_window > 1
                 OR c.suspicious_images > 0)
            """;

    private final EntityManager entityManager;

    /**
     * @param from     inclusive
     * @param to       exclusive
     * @param branchId branch ("ramo") of the claim's cause; null for every branch
     * @param riskBand alert level; null for every band, including the cases the scoring never ran on
     */
    @Transactional(readOnly = true)
    public List<FraudReportRow> findFlaggedBetween(Instant from, Instant to, Long branchId,
                                                   RiskBand riskBand) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .addValue("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .addValue("fraudConfirmed", ExpertVerdict.FRAUD_CONFIRMED.name())
                .addValue("highBands", List.of(RiskBand.HIGH.name(), RiskBand.CRITICAL.name()));
        StringBuilder sql = new StringBuilder(FLAGGED_CASES);
        // Appended rather than `:branchId IS NULL OR ...`: Postgres can't infer the type of a
        // parameter that is only ever compared to NULL and rejects the statement. Same reason
        // ResolvedCaseRepository appends its own cuts.
        if (branchId != null) {
            sql.append("   AND cc.branch_id = :branchId\n");
            params.addValue("branchId", branchId);
        }
        if (riskBand != null) {
            sql.append("   AND c.risk_band = :riskBand\n");
            params.addValue("riskBand", riskBand.name());
        }
        sql.append(" ORDER BY c.reported_at DESC, c.id DESC");

        // suppressClose: the connection is Hibernate's and Hibernate closes it.
        List<FraudReportRow> rows = entityManager.unwrap(Session.class).doReturningWork(connection ->
                new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection, true))
                        .query(sql.toString(), params, (rs, rowNum) -> toRow(rs)));
        // The WHERE above pushes the same three conditions into the database so a period doesn't
        // drag every case into memory. signalsOf is the definition: anything that got through
        // without a signal would be a row the report can't explain, so it doesn't go out.
        return rows.stream().filter(row -> !row.signals().isEmpty()).toList();
    }

    /**
     * The branch's name, so the report can say what it was filtered by even when the filter matched
     * nothing — a document that doesn't name its own filter is indistinguishable from an unfiltered
     * one. Its own copy of the read rather than a shared one, same as each read-side here carries
     * the SQL it needs ({@code ResolvedCaseRepository} and {@code ClaimMetricsRepository} already
     * keep a resolution CTE each).
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

    private static FraudReportRow toRow(ResultSet rs) throws SQLException {
        String band = rs.getString("risk_band");
        RiskBand riskBand = band == null ? null : RiskBand.valueOf(band);
        int claimsInWindow = rs.getInt("claims_in_window");
        int suspiciousImages = rs.getInt("suspicious_images");
        return new FraudReportRow(
                rs.getLong("id"),
                fullName(rs.getString("insured_name"), rs.getString("insured_surname")),
                rs.getString("dni"),
                rs.getString("branch"),
                rs.getString("claim_cause"),
                rs.getObject("reported_at", OffsetDateTime.class).toInstant(),
                riskBand,
                signalsOf(riskBand, claimsInWindow, suspiciousImages),
                claimsInWindow,
                suspiciousImages,
                CaseStatus.valueOf(rs.getString("status")),
                rs.getBoolean("fraud_determined"),
                rs.getBoolean("expert_backed"));
    }

    /** The one definition of what "suspicious" means here; the SQL predicate mirrors it. */
    private static List<FraudSignal> signalsOf(RiskBand riskBand, int claimsInWindow,
                                               int suspiciousImages) {
        List<FraudSignal> signals = new ArrayList<>(FraudSignal.values().length);
        if (riskBand == RiskBand.HIGH || riskBand == RiskBand.CRITICAL) {
            signals.add(FraudSignal.HIGH_RISK_SCORE);
        }
        if (claimsInWindow > 1) {
            signals.add(FraudSignal.REPEAT_CLAIMANT);
        }
        if (suspiciousImages > 0) {
            signals.add(FraudSignal.FORENSIC_INCONSISTENCY);
        }
        return List.copyOf(signals);
    }

    private static String fullName(String name, String surname) {
        return name == null ? null : name + " " + surname;
    }
}
