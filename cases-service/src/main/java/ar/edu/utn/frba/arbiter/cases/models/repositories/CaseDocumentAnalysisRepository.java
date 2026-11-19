package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.dto.DocumentAnalysisSummary;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExtractedField;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads classification-service's {@code document_analysis} tables directly, under the same
 * read-only, plain-JDBC terms as {@link CaseAnalysisRepository}, and on Hibernate's connection for the
 * same {@code search_path} reason. Detail only: deliberately not batched, so the inbox never calls it.
 */
@Repository
@RequiredArgsConstructor
public class CaseDocumentAnalysisRepository {

    private final EntityManager entityManager;

    /** Ordered by document type so the tab doesn't reshuffle between reloads. */
    @Transactional(readOnly = true)
    public List<DocumentAnalysisSummary> findByCaseId(Long caseId) {
        if (caseId == null) {
            return List.of();
        }
        // suppressClose: Hibernate owns this connection and closes it; the template must not.
        return entityManager.unwrap(Session.class).doReturningWork(connection ->
                query(new NamedParameterJdbcTemplate(
                        new SingleConnectionDataSource(connection, true)), caseId));
    }

    private List<DocumentAnalysisSummary> query(NamedParameterJdbcTemplate jdbcTemplate, Long caseId) {
        List<Row> rows = jdbcTemplate.query("""
                SELECT a.id, a.transcription, a.document_date, a.amount, a.item_description,
                       a.affected_party, a.extraction_status, d.type, d.id AS document_id
                  FROM document_analysis a
                  JOIN case_documents d ON d.id = a.case_document_id
                 WHERE d.case_id = :caseId
                 ORDER BY d.type
                """, Map.of("caseId", caseId), (rs, rowNum) -> {
            Date documentDate = rs.getDate("document_date");
            return new Row(
                    rs.getLong("id"),
                    rs.getString("type"),
                    rs.getLong("document_id"),
                    rs.getString("transcription"),
                    documentDate == null ? null : documentDate.toLocalDate(),
                    rs.getBigDecimal("amount"),
                    rs.getString("item_description"),
                    rs.getString("affected_party"),
                    rs.getString("extraction_status"));
        });

        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> analysisIds = rows.stream().map(Row::analysisId).toList();
        Map<Long, List<String>> findingsByAnalysis = findingsFor(jdbcTemplate, analysisIds);
        Map<Long, List<DocumentAnalysisSummary.Detail>> attributesByAnalysis =
                pairsFrom("document_attribute", jdbcTemplate, analysisIds);
        Map<Long, List<DocumentAnalysisSummary.Detail>> detailsByAnalysis =
                pairsFrom("document_detail", jdbcTemplate, analysisIds);
        Map<Long, List<DocumentAnalysisSummary.Correction>> correctionsByDocument =
                correctionsFor(jdbcTemplate, rows.stream().map(Row::documentId).toList());

        return rows.stream()
                .map(row -> new DocumentAnalysisSummary(
                        row.documentType(),
                        row.documentId(),
                        row.transcription(),
                        row.documentDate(),
                        row.amount(),
                        row.itemDescription(),
                        attributesByAnalysis.getOrDefault(row.analysisId(), List.of()),
                        row.affectedParty(),
                        findingsByAnalysis.getOrDefault(row.analysisId(), List.of()),
                        detailsByAnalysis.getOrDefault(row.analysisId(), List.of()),
                        row.extractionStatus(),
                        correctionsByDocument.getOrDefault(row.documentId(), List.of())))
                .toList();
    }

    /**
     * The name/value rows of {@code table}, in insertion order: for details, the order the model read
     * them off the document; for item attributes, the order the policy lists them in.
     */
    private Map<Long, List<DocumentAnalysisSummary.Detail>> pairsFrom(
            String table, NamedParameterJdbcTemplate jdbcTemplate, List<Long> analysisIds) {
        Map<Long, List<DocumentAnalysisSummary.Detail>> byAnalysis = new HashMap<>();
        jdbcTemplate.query("""
                SELECT analysis_id, name, value
                  FROM %s
                 WHERE analysis_id IN (:analysisIds)
                 ORDER BY id
                """.formatted(table), Map.of("analysisIds", analysisIds), (rs, rowNum) ->
                byAnalysis.computeIfAbsent(rs.getLong("analysis_id"), key -> new ArrayList<>())
                        .add(new DocumentAnalysisSummary.Detail(
                                rs.getString("name"), rs.getString("value"))));
        return byAnalysis;
    }

    private Map<Long, List<String>> findingsFor(NamedParameterJdbcTemplate jdbcTemplate,
                                                List<Long> analysisIds) {
        Map<Long, List<String>> byAnalysis = new HashMap<>();
        // RowMapper, not RowCallbackHandler: with a block lambda both query() overloads compile.
        // The result is discarded.
        jdbcTemplate.query("""
                SELECT analysis_id, finding
                  FROM document_visual_finding
                 WHERE analysis_id IN (:analysisIds)
                 ORDER BY id
                """, Map.of("analysisIds", analysisIds), (rs, rowNum) ->
                byAnalysis.computeIfAbsent(rs.getLong("analysis_id"), key -> new ArrayList<>())
                        .add(rs.getString("finding")));
        return byAnalysis;
    }

    private Map<Long, List<DocumentAnalysisSummary.Correction>> correctionsFor(
            NamedParameterJdbcTemplate jdbcTemplate, List<Long> documentIds) {
        Map<Long, List<DocumentAnalysisSummary.Correction>> byDocument = new HashMap<>();
        jdbcTemplate.query("""
                SELECT c.case_document_id, c.field, c.field_name, c.previous_value, c.new_value,
                       c.corrected_at, an.name, an.surname
                  FROM document_analysis_correction c
                  JOIN claims_analyst an ON an.id = c.analyst_id
                 WHERE c.case_document_id IN (:documentIds)
                 ORDER BY c.corrected_at, c.id
                """, Map.of("documentIds", documentIds), (rs, rowNum) ->
                byDocument.computeIfAbsent(rs.getLong("case_document_id"), key -> new ArrayList<>())
                        .add(new DocumentAnalysisSummary.Correction(
                                ExtractedField.valueOf(rs.getString("field")),
                                rs.getString("field_name"),
                                rs.getString("previous_value"),
                                rs.getString("new_value"),
                                (rs.getString("name") + " " + rs.getString("surname")).trim(),
                                rs.getTimestamp("corrected_at").toInstant())));
        return byDocument;
    }

    private record Row(
            Long analysisId,
            String documentType,
            Long documentId,
            String transcription,
            LocalDate documentDate,
            BigDecimal amount,
            String itemDescription,
            String affectedParty,
            String extractionStatus
    ) {
    }
}
