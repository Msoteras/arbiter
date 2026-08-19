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
 * What the vision pass read out of each attachment of a case, straight from
 * {@code document_analysis} / {@code document_visual_finding} (H0031).
 *
 * <p>Same deliberate exception to "each module owns its tables" as {@link CaseAnalysisRepository},
 * and for the same reason: both tables live in the <b>same tenant schema</b> as {@code cases}, so
 * this is one query instead of a REST hop. Plain JDBC over a narrow, named set of columns rather
 * than entities, so cases-service never claims ownership of them — classification-service writes
 * them, this only reads.
 *
 * <p><b>Detail only, not batched.</b> Unlike {@code CaseAnalysisRepository}, there is no
 * {@code findByCaseIds}: this feeds one open case, never a page of the inbox. Adding a batched
 * version would invite calling it from the listing, which is the join-per-row this codebase
 * already avoids elsewhere.
 *
 * <p><b>Las queries corren sobre la conexión de Hibernate, no sobre una del pool</b> — las tablas
 * se nombran sin calificar y dependen del {@code search_path} que setea {@code
 * TenantConnectionProvider}, que sólo interviene en las conexiones que pide Hibernate. Ver el
 * javadoc de {@link CaseAnalysisRepository}, donde está el detalle de por qué.
 */
@Repository
@RequiredArgsConstructor
public class CaseDocumentAnalysisRepository {

    private final EntityManager entityManager;

    /**
     * @return one entry per attachment already read by the model, ordered by document type so the
     *         tab doesn't reshuffle between reloads. Empty when the case was never classified, was
     *         fast tracked without reading anything, or was classified before this table existed —
     *         all three are ordinary, and the tab simply doesn't show.
     */
    @Transactional(readOnly = true)
    public List<DocumentAnalysisSummary> findByCaseId(Long caseId) {
        if (caseId == null) {
            return List.of();
        }
        // suppressClose: la conexión es de Hibernate y la cierra Hibernate; el template no debe.
        return entityManager.unwrap(Session.class).doReturningWork(connection ->
                query(new NamedParameterJdbcTemplate(
                        new SingleConnectionDataSource(connection, true)), caseId));
    }

    private List<DocumentAnalysisSummary> query(NamedParameterJdbcTemplate jdbcTemplate, Long caseId) {
        List<Row> rows = jdbcTemplate.query("""
                SELECT a.id, a.transcription, a.document_date, a.amount, a.item_description,
                       a.imei, a.affected_party, d.type
                  FROM document_analysis a
                  JOIN case_documents d ON d.id = a.case_document_id
                 WHERE d.case_id = :caseId
                 ORDER BY d.type
                """, Map.of("caseId", caseId), (rs, rowNum) -> {
            // getDate devuelve java.sql.Date y null si la columna es NULL, que es el caso normal
            // para un documento que no trae fecha — de ahí el chequeo antes de convertir.
            Date documentDate = rs.getDate("document_date");
            return new Row(
                    rs.getLong("id"),
                    rs.getString("type"),
                    rs.getString("transcription"),
                    documentDate == null ? null : documentDate.toLocalDate(),
                    rs.getBigDecimal("amount"),
                    rs.getString("item_description"),
                    rs.getString("imei"),
                    rs.getString("affected_party"));
        });

        if (rows.isEmpty()) {
            return List.of();
        }

        Map<Long, List<String>> findingsByAnalysis = findingsFor(
                jdbcTemplate, rows.stream().map(Row::analysisId).toList());

        return rows.stream()
                .map(row -> new DocumentAnalysisSummary(
                        row.documentType(),
                        row.transcription(),
                        row.documentDate(),
                        row.amount(),
                        row.itemDescription(),
                        row.imei(),
                        row.affectedParty(),
                        findingsByAnalysis.getOrDefault(row.analysisId(), List.of())))
                .toList();
    }

    private Map<Long, List<String>> findingsFor(NamedParameterJdbcTemplate jdbcTemplate,
                                                List<Long> analysisIds) {
        Map<Long, List<String>> byAnalysis = new HashMap<>();
        // RowMapper y no RowCallbackHandler: con un lambda de bloque las dos sobrecargas de query()
        // compilan, y ésta es la forma que ya usa CaseAnalysisRepository. El resultado se descarta.
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
            String imei,
            String affectedParty
    ) {
    }
}
