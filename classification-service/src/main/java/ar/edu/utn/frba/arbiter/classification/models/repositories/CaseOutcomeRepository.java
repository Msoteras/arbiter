package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationFailureReason;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * The few {@code cases} columns this module writes and reads directly: outcomes with no table on this
 * side (e.g. {@code was_fast_track}, since a Fast Track writes no {@code llm_analysis} row). Plain
 * JDBC on named columns so {@code cases} isn't mapped by two modules.
 */
@Repository
@RequiredArgsConstructor
public class CaseOutcomeRepository {

    private final JdbcTemplate jdbcTemplate;

    /** Raw {@code JdbcTemplate} connections aren't tenant-routed, so every table is qualified. */
    private static String schema() {
        return TenantContext.schemaForSql();
    }

    public void markFastTracked(Long caseId) {
        jdbcTemplate.update(
                "UPDATE %s.cases SET was_fast_track = TRUE WHERE id = ?".formatted(schema()), caseId);
    }

    public void saveForensicReport(Long caseId, String reportJson) {
        jdbcTemplate.update(
                "UPDATE %s.cases SET forensic_report = ?::jsonb WHERE id = ?".formatted(schema()),
                reportJson, caseId);
    }

    /** Stored with the score, not derived later: the referente may retune the weights afterwards. */
    public void saveScoringConfiguration(Long caseId, Long scoringConfigurationId) {
        jdbcTemplate.update(
                "UPDATE %s.cases SET scoring_configuration_id = ? WHERE id = ?".formatted(schema()),
                scoringConfigurationId, caseId);
    }

    /** A 0-row update is fine: the case's own insert may not have committed yet. */
    public void recordClassificationFailure(Long caseId, ClassificationFailureReason reason, String message) {
        if (caseId == null) {
            return;
        }
        jdbcTemplate.update(
                ("UPDATE %s.cases SET classification_failure_reason = ?, "
                        + "classification_failure_message = ? WHERE id = ?").formatted(schema()),
                reason.name(), message, caseId);
    }

    public void clearClassificationFailure(Long caseId) {
        if (caseId == null) {
            return;
        }
        jdbcTemplate.update(
                ("UPDATE %s.cases SET classification_failure_reason = NULL, "
                        + "classification_failure_message = NULL WHERE id = ?").formatted(schema()),
                caseId);
    }

    // The rest of the read model is copied by cases-service's poller from getStatus; don't add a
    // second write path here.

    public CaseOutcome findOutcome(Long caseId) {
        List<CaseOutcome> rows = jdbcTemplate.query("""
                        SELECT c.was_fast_track, c.forensic_report, i.name, i.surname
                          FROM %1$s.cases c
                          JOIN %1$s.insured i ON i.id = c.insured_id
                         WHERE c.id = ?
                        """.formatted(schema()),
                (rs, rowNum) -> new CaseOutcome(
                        rs.getBoolean("was_fast_track"),
                        rs.getString("forensic_report"),
                        rs.getString("name") + " " + rs.getString("surname")),
                caseId);
        return rows.isEmpty() ? CaseOutcome.unknown() : rows.getFirst();
    }

    /** @param insuredName null when the case isn't in this schema */
    public record CaseOutcome(boolean wasFastTrack, String forensicReport, String insuredName) {

        static CaseOutcome unknown() {
            return new CaseOutcome(false, null, null);
        }
    }
}
