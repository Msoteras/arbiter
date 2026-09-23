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
 * Read side of the fraud report. Same direct, read-only access to other modules' tables on Hibernate's
 * connection as {@link ResolvedCaseRepository}, for the same reasons.
 */
@Repository
@RequiredArgsConstructor
public class FlaggedCaseRepository {

    /**
     * Correlated subqueries rather than joins: they count over a different grain than the row, and
     * joining would multiply the case by its documents.
     *
     * <p>The 12-month window is counted from each case's own {@code reported_at}, so a row reads the
     * same whenever the report is run. {@code claims_in_window} is context only, not a signal.
     *
     * <p>{@code document_inconsistency_note} only reads the case's LATEST {@code risk_analysis}, so a
     * case reclassified after its documentation was fixed reads clean, like {@code cases.risk_band}.
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
                       ) AS suspicious_images,
                       (SELECT item ->> 'rationale'
                          FROM (SELECT risk_breakdown
                                  FROM risk_analysis ra
                                 WHERE ra.case_id = c.id
                                 ORDER BY ra.analyzed_at DESC
                                 LIMIT 1) latest
                          CROSS JOIN LATERAL jsonb_array_elements(latest.risk_breakdown) item
                         WHERE item ->> 'factorId' = 'document_inconsistency'
                           AND (item ->> 'rawScore')::numeric > 0
                         LIMIT 1
                       ) AS document_inconsistency_note
                  FROM cases c
                 WHERE c.reported_at >= :from AND c.reported_at < :to
            )
            SELECT c.id, c.reported_at, c.risk_band, c.fraud_determined,
                   c.claims_in_window, c.suspicious_images, c.document_inconsistency_note,
                   i.name AS insured_name, i.surname AS insured_surname, i.dni,
                   b.name AS branch, cc.name AS claim_cause, s.name AS status,
                   EXISTS (SELECT 1 FROM expert_assessment ea
                            WHERE ea.case_id = c.id AND ea.verdict = :fraudConfirmed) AS expert_backed,
                   (CASE WHEN c.risk_band IN (:highBands) THEN 1 ELSE 0 END
                  + CASE WHEN c.suspicious_images > 0 THEN 1 ELSE 0 END
                  + CASE WHEN c.document_inconsistency_note IS NOT NULL THEN 1 ELSE 0 END
                   ) AS signal_count
              FROM candidate c
              JOIN claim_cause cc ON cc.id = c.claim_cause_id
              JOIN branch b       ON b.id = cc.branch_id
              JOIN case_status s  ON s.id = c.current_status_id
              JOIN insured i      ON i.id = c.insured_id
             WHERE (c.risk_band IN (:highBands)
                 OR c.suspicious_images > 0
                 OR c.document_inconsistency_note IS NOT NULL)
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
        // parameter that is only ever compared to NULL and rejects the statement.
        if (branchId != null) {
            sql.append("   AND cc.branch_id = :branchId\n");
            params.addValue("branchId", branchId);
        }
        if (riskBand != null) {
            sql.append("   AND c.risk_band = :riskBand\n");
            params.addValue("riskBand", riskBand.name());
        }
        // Coinciding signals first, then the higher band. Sorted here rather than in each surface so
        // the screen, the CSV and the PDF all lead with the same case.
        params.addValue("criticalBand", RiskBand.CRITICAL.name())
                .addValue("highBand", RiskBand.HIGH.name());
        sql.append("""
                 ORDER BY signal_count DESC,
                          CASE WHEN c.risk_band = :criticalBand THEN 2
                               WHEN c.risk_band = :highBand THEN 1
                               ELSE 0 END DESC,
                          c.reported_at DESC, c.id DESC""");

        // suppressClose: the connection is Hibernate's and Hibernate closes it.
        List<FraudReportRow> rows = entityManager.unwrap(Session.class).doReturningWork(connection ->
                new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection, true))
                        .query(sql.toString(), params, (rs, rowNum) -> toRow(rs)));
        // The WHERE only pre-filters; signalsOf is the definition, and a row without a signal
        // would be one the report can't explain.
        return rows.stream().filter(row -> !row.signals().isEmpty()).toList();
    }

    /**
     * Every claim filed in the period and branch, flagged or not: the report's denominator. No
     * alert-level cut, so the share stays readable when the screen is filtered to one band.
     *
     * @param from inclusive
     * @param to   exclusive
     */
    @Transactional(readOnly = true)
    public long countClaimsBetween(Instant from, Instant to, Long branchId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .addValue("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
        StringBuilder sql = new StringBuilder("""
                SELECT count(*)
                  FROM cases c
                  JOIN claim_cause cc ON cc.id = c.claim_cause_id
                 WHERE c.reported_at >= :from AND c.reported_at < :to
                """);
        if (branchId != null) {
            sql.append("   AND cc.branch_id = :branchId\n");
            params.addValue("branchId", branchId);
        }
        Long total = entityManager.unwrap(Session.class).doReturningWork(connection ->
                new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection, true))
                        .queryForObject(sql.toString(), params, Long.class));
        return total == null ? 0 : total;
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

    private static FraudReportRow toRow(ResultSet rs) throws SQLException {
        String band = rs.getString("risk_band");
        RiskBand riskBand = band == null ? null : RiskBand.valueOf(band);
        int claimsInWindow = rs.getInt("claims_in_window");
        int suspiciousImages = rs.getInt("suspicious_images");
        String documentInconsistencyNote = rs.getString("document_inconsistency_note");
        return new FraudReportRow(
                rs.getLong("id"),
                fullName(rs.getString("insured_name"), rs.getString("insured_surname")),
                rs.getString("dni"),
                rs.getString("branch"),
                rs.getString("claim_cause"),
                rs.getObject("reported_at", OffsetDateTime.class).toInstant(),
                riskBand,
                signalsOf(riskBand, suspiciousImages, documentInconsistencyNote),
                claimsInWindow,
                suspiciousImages,
                documentInconsistencyNote,
                CaseStatus.valueOf(rs.getString("status")),
                rs.getBoolean("fraud_determined"),
                rs.getBoolean("expert_backed"));
    }

    /** The one definition of a fraud signal; the SQL predicate mirrors it. */
    private static List<FraudSignal> signalsOf(RiskBand riskBand, int suspiciousImages,
                                               String documentInconsistencyNote) {
        List<FraudSignal> signals = new ArrayList<>(FraudSignal.values().length);
        if (riskBand == RiskBand.HIGH || riskBand == RiskBand.CRITICAL) {
            signals.add(FraudSignal.HIGH_RISK_SCORE);
        }
        if (suspiciousImages > 0) {
            signals.add(FraudSignal.FORENSIC_INCONSISTENCY);
        }
        if (documentInconsistencyNote != null) {
            signals.add(FraudSignal.DOCUMENT_INCONSISTENCY);
        }
        return List.copyOf(signals);
    }

    private static String fullName(String name, String surname) {
        return name == null ? null : name + " " + surname;
    }
}
