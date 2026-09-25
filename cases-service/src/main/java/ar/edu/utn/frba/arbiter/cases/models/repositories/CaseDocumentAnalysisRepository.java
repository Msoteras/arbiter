package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.dto.DocumentAnalysisSummary;
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
                       a.brand, a.model, a.imei, a.affected_party, a.extraction_status, d.type
                  FROM document_analysis a
                  JOIN case_documents d ON d.id = a.case_document_id
                 WHERE d.case_id = :caseId
                 ORDER BY d.type
                """, Map.of("caseId", caseId), (rs, rowNum) -> {
            Date documentDate = rs.getDate("document_date");
            return new Row(
                    rs.getLong("id"),
                    rs.getString("type"),
                    rs.getString("transcription"),
                    documentDate == null ? null : documentDate.toLocalDate(),
                    rs.getBigDecimal("amount"),
                    rs.getString("item_description"),
                    rs.getString("brand"),
                    rs.getString("model"),
                    rs.getString("imei"),
                    rs.getString("affected_party"),
                    rs.getString("extraction_status"));
        });

        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> analysisIds = rows.stream().map(Row::analysisId).toList();
        Map<Long, List<String>> findingsByAnalysis = findingsFor(jdbcTemplate, analysisIds);
        Map<Long, List<DocumentAnalysisSummary.Detail>> detailsByAnalysis =
                detailsFor(jdbcTemplate, analysisIds);

        return rows.stream()
                .map(row -> new DocumentAnalysisSummary(
                        row.documentType(),
                        row.transcription(),
                        row.documentDate(),
                        row.amount(),
                        row.itemDescription(),
                        row.brand(),
                        row.model(),
                        row.imei(),
                        row.affectedParty(),
                        findingsByAnalysis.getOrDefault(row.analysisId(), List.of()),
                        detailsByAnalysis.getOrDefault(row.analysisId(), List.of()),
                        row.extractionStatus()))
                .toList();
    }

    /** Insertion order, which is the order the model read them off the document. */
    private Map<Long, List<DocumentAnalysisSummary.Detail>> detailsFor(
            NamedParameterJdbcTemplate jdbcTemplate, List<Long> analysisIds) {
        Map<Long, List<DocumentAnalysisSummary.Detail>> byAnalysis = new HashMap<>();
        jdbcTemplate.query("""
                SELECT analysis_id, name, value
                  FROM document_detail
                 WHERE analysis_id IN (:analysisIds)
                 ORDER BY id
                """, Map.of("analysisIds", analysisIds), (rs, rowNum) ->
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

    private record Row(
            Long analysisId,
            String documentType,
            String transcription,
            LocalDate documentDate,
            BigDecimal amount,
            String itemDescription,
            String brand,
            String model,
            String imei,
            String affectedParty,
            String extractionStatus
    ) {
    }
}
