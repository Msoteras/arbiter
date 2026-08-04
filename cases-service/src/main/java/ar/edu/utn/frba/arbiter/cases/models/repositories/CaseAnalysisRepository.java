package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.RiskBreakdownJsonConverter;
import ar.edu.utn.frba.arbiter.common.dto.RiskBreakdownItem;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The classification detail that is NOT denormalized onto {@code cases}, read straight from
 * {@code llm_analysis} / {@code risk_analysis}.
 *
 * <p>Only what the analyst's inbox <b>filters</b> by lives as a column on {@code cases}
 * ({@code risk_band}, status, …); what is merely <b>displayed</b> is joined. That's why the
 * recommendation, its confidence, its reasons and the score breakdown are here instead.
 *
 * <p>A deliberate exception to "each module owns its tables", the mirror image of
 * classification-service's {@code CaseOutcomeRepository}, which reads and writes {@code cases}
 * and joins {@code case_documents}. Both tables live in the <b>same tenant schema</b> as
 * {@code cases}, so this is one query, not a REST hop — doing it over HTTP for every row of a
 * paged inbox is not an option. Plain JDBC and a narrow, named set of columns rather than
 * entities, so no second module claims ownership of them.
 *
 * <p>Both tables are append-only (one row per run), so "the classification" always means the
 * newest row for the case — hence {@code DISTINCT ON ... ORDER BY id DESC} rather than a plain
 * lookup, which would return every historical run.
 *
 * <p><b>Las queries corren sobre la conexión de Hibernate, no sobre una del pool.</b> Estas dos
 * tablas viven en el esquema del tenant y se nombran sin calificar, así que dependen del
 * {@code search_path} — y ese lo setea {@code TenantConnectionProvider}, que sólo interviene en
 * las conexiones que pide Hibernate. Un {@code NamedParameterJdbcTemplate} armado sobre el
 * {@code DataSource} las pediría directo al pool, sin ese seteo, y las queries fallarían con
 * "relation llm_analysis does not exist" contra la base multi-tenant real (en los tests no se
 * ve, porque ahí el esquema es plano y todo cae en {@code public}).
 */
@Repository
@RequiredArgsConstructor
public class CaseAnalysisRepository {

    private final EntityManager entityManager;

    private static final RiskBreakdownJsonConverter BREAKDOWN_JSON = new RiskBreakdownJsonConverter();

    /**
     * @param classification null when the model never ran (a Fast Track leaves no row, and neither
     *                       does a case still being classified)
     */
    public record CaseAnalysis(
            Classification classification,
            Double confidence,
            List<String> factors,
            List<RiskBreakdownItem> riskBreakdown
    ) {
        public static CaseAnalysis none() {
            return new CaseAnalysis(null, null, List.of(), null);
        }
    }

    public CaseAnalysis findByCaseId(Long caseId) {
        return findByCaseIds(List.of(caseId)).getOrDefault(caseId, CaseAnalysis.none());
    }

    /**
     * Batched on purpose: the inbox maps a whole page of cases, and one query per row is the
     * N+1 that makes a paged list collapse.
     *
     * <p>{@code @Transactional} para que haya una sesión de Hibernate de la cual tomar la
     * conexión: los métodos de {@code CaseServiceImpl} que llaman acá no son transaccionales, y
     * con {@code open-in-view} apagado no hay ninguna abierta por el request.
     */
    @Transactional(readOnly = true)
    public Map<Long, CaseAnalysis> findByCaseIds(Collection<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return Map.of();
        }
        // suppressClose: la conexión es de Hibernate y la cierra Hibernate; el template no debe.
        return entityManager.unwrap(Session.class).doReturningWork(connection ->
                queryAnalysis(new NamedParameterJdbcTemplate(
                        new SingleConnectionDataSource(connection, true)), caseIds));
    }

    private Map<Long, CaseAnalysis> queryAnalysis(NamedParameterJdbcTemplate jdbcTemplate,
                                                  Collection<Long> caseIds) {
        Map<String, Object> params = Map.of("caseIds", caseIds);

        List<LatestAnalysis> latest = jdbcTemplate.query("""
                SELECT DISTINCT ON (case_id) case_id, id, recommendation, confidence
                  FROM llm_analysis
                 WHERE case_id IN (:caseIds)
                 ORDER BY case_id, id DESC
                """, params, (rs, rowNum) -> {
            // La columna es numeric(5,3) y el driver de Postgres no la convierte a Double con
            // getObject(name, Class): hay que leerla como BigDecimal. Sigue viajando como Double
            // porque es lo que expone ClaimResponse/CaseResponse.
            BigDecimal rawConfidence = rs.getBigDecimal("confidence");
            Double confidence = rawConfidence == null ? null : rawConfidence.doubleValue();
            return new LatestAnalysis(
                    rs.getLong("case_id"),
                    rs.getLong("id"),
                    Classification.valueOf(rs.getString("recommendation")),
                    confidence);
        });

        Map<Long, LatestAnalysis> analysisByCase = new HashMap<>();
        Map<Long, Long> caseByAnalysisId = new HashMap<>();
        for (LatestAnalysis row : latest) {
            analysisByCase.put(row.caseId(), row);
            caseByAnalysisId.put(row.analysisId(), row.caseId());
        }

        Map<Long, List<String>> factorsByCase = factorsFor(jdbcTemplate, caseByAnalysisId);

        Map<Long, List<RiskBreakdownItem>> breakdownByCase = new HashMap<>();
        jdbcTemplate.query("""
                SELECT DISTINCT ON (case_id) case_id, risk_breakdown
                  FROM risk_analysis
                 WHERE case_id IN (:caseIds)
                 ORDER BY case_id, id DESC
                """, params, (rs, rowNum) -> breakdownByCase.put(
                rs.getLong("case_id"),
                BREAKDOWN_JSON.convertToEntityAttribute(rs.getString("risk_breakdown"))));

        Map<Long, CaseAnalysis> result = new HashMap<>();
        for (Long caseId : caseIds) {
            LatestAnalysis row = analysisByCase.get(caseId);
            result.put(caseId, new CaseAnalysis(
                    row == null ? null : row.classification(),
                    row == null ? null : row.confidence(),
                    factorsByCase.getOrDefault(caseId, List.of()),
                    breakdownByCase.get(caseId)));
        }
        return result;
    }

    private record LatestAnalysis(Long caseId, Long analysisId, Classification classification, Double confidence) {
    }

    private Map<Long, List<String>> factorsFor(NamedParameterJdbcTemplate jdbcTemplate,
                                               Map<Long, Long> caseByAnalysisId) {
        if (caseByAnalysisId.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> factorsByCase = new HashMap<>();
        jdbcTemplate.query("""
                SELECT analysis_id, reason
                  FROM llm_reason
                 WHERE analysis_id IN (:analysisIds)
                 ORDER BY id
                """, Map.of("analysisIds", caseByAnalysisId.keySet()), (rs, rowNum) -> {
            Long caseId = caseByAnalysisId.get(rs.getLong("analysis_id"));
            return factorsByCase.computeIfAbsent(caseId, key -> new ArrayList<>())
                    .add(rs.getString("reason"));
        });
        return factorsByCase;
    }
}
