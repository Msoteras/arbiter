package ar.edu.utn.frba.arbiter.classification.models.repositories;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * The few {@code cases} columns this module writes and reads directly.
 *
 * <p>A deliberate exception to "each module owns its tables". Classification runs
 * asynchronously: by the time cases-service polls for the result, the request that produced it
 * is long gone, so whatever the analysis decided has to be durable somewhere. Two of those
 * outcomes have no table on this side —
 *
 * <ul>
 *   <li>{@code was_fast_track}: a deterministic Fast Track produces no {@code llm_analysis} row
 *       (the model never ran, and the table's CHECK rejects {@code FAST_TRACK}), so "no row"
 *       would be indistinguishable from "not classified yet".</li>
 *   <li>{@code forensic_report}: the per-image findings are normalized into
 *       {@code image_analysis}, but the assembled report the analyst UI renders is not.</li>
 * </ul>
 *
 * <p>Plain JDBC rather than an entity on purpose: mapping {@code cases} here would mean two
 * modules owning one entity, which is exactly what the rule protects against. This is a narrow,
 * named set of columns instead.
 */
@Repository
@RequiredArgsConstructor
public class CaseOutcomeRepository {

    private final JdbcTemplate jdbcTemplate;

    /** Records that the deterministic gate resolved this case, with no model run. */
    public void markFastTracked(Long caseId) {
        jdbcTemplate.update("UPDATE cases SET was_fast_track = TRUE WHERE id = ?", caseId);
    }

    /** The cast is needed because the column is jsonb and the codec hands us plain text. */
    public void saveForensicReport(Long caseId, String reportJson) {
        jdbcTemplate.update("UPDATE cases SET forensic_report = ?::jsonb WHERE id = ?", reportJson, caseId);
    }

    // The rest of the read model (analysis_classification, risk_score, risk_band, ...) is NOT
    // written here: cases-service's poller already copies it off the getStatus response. A
    // second write path for the same values is how they drift apart.

    /**
     * Everything {@code getStatus} needs from the case itself, in one round trip. The insured's
     * name is joined rather than stored on the case: it lives on {@code insured} now, and copying
     * it onto every classification is what the old log did.
     */
    public CaseOutcome findOutcome(Long caseId) {
        List<CaseOutcome> rows = jdbcTemplate.query("""
                        SELECT c.was_fast_track, c.forensic_report, i.name, i.surname
                          FROM cases c
                          JOIN insured i ON i.id = c.insured_id
                         WHERE c.id = ?
                        """,
                (rs, rowNum) -> new CaseOutcome(
                        rs.getBoolean("was_fast_track"),
                        rs.getString("forensic_report"),
                        rs.getString("name") + " " + rs.getString("surname")),
                caseId);
        return rows.isEmpty() ? CaseOutcome.unknown() : rows.getFirst();
    }

    /** @param insuredName null when the case isn't in this schema (isolated classification) */
    public record CaseOutcome(boolean wasFastTrack, String forensicReport, String insuredName) {

        static CaseOutcome unknown() {
            return new CaseOutcome(false, null, null);
        }
    }
}
