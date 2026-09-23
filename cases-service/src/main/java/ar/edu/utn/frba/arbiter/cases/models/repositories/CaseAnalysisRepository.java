package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.RiskBreakdownJsonConverter;
import ar.edu.utn.frba.arbiter.common.dto.RiskBreakdownItem;
import ar.edu.utn.frba.arbiter.common.enums.CauseConsistency;
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
 * Reads classification-service's {@code llm_analysis} / {@code risk_analysis} directly: a deliberate
 * exception to table ownership, since they share the tenant schema with {@code cases} and a REST hop
 * per inbox row is not an option. Plain JDBC over named columns, so this module never claims them.
 * Both tables are append-only, hence {@code DISTINCT ON ... ORDER BY id DESC} to get the newest run.
 *
 * <p>Queries run on Hibernate's connection, not one from the pool: the tables are unqualified and
 * rely on the {@code search_path} that {@code TenantConnectionProvider} only sets on Hibernate's
 * connections. Tests don't catch this because their schema is flat.
 */
@Repository
@RequiredArgsConstructor
public class CaseAnalysisRepository {

    private final EntityManager entityManager;

    private static final RiskBreakdownJsonConverter BREAKDOWN_JSON = new RiskBreakdownJsonConverter();

    /** @param classification null when the model never ran (Fast Track, or still classifying) */
    public record CaseAnalysis(
            Classification classification,
            Double confidence,
            List<String> factors,
            List<RiskBreakdownItem> riskBreakdown,
            /** Null means "not evaluated", never {@code MATCHES}. */
            CauseConsistency causeConsistency,
            String suggestedClaimCause,
            String causeEvidence
    ) {
        public static CaseAnalysis none() {
            return new CaseAnalysis(null, null, List.of(), null, null, null, null);
        }
    }

    public CaseAnalysis findByCaseId(Long caseId) {
        return findByCaseIds(List.of(caseId)).getOrDefault(caseId, CaseAnalysis.none());
    }

    /**
     * Batched to avoid N+1 on the inbox. {@code @Transactional} provides the Hibernate session to take
     * the connection from: callers aren't transactional and {@code open-in-view} is off.
     */
    @Transactional(readOnly = true)
    public Map<Long, CaseAnalysis> findByCaseIds(Collection<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return Map.of();
        }
        // suppressClose: Hibernate owns this connection and closes it; the template must not.
        return entityManager.unwrap(Session.class).doReturningWork(connection ->
                queryAnalysis(new NamedParameterJdbcTemplate(
                        new SingleConnectionDataSource(connection, true)), caseIds));
    }

    private Map<Long, CaseAnalysis> queryAnalysis(NamedParameterJdbcTemplate jdbcTemplate,
                                                  Collection<Long> caseIds) {
        Map<String, Object> params = Map.of("caseIds", caseIds);

        List<LatestAnalysis> latest = jdbcTemplate.query("""
                SELECT DISTINCT ON (case_id) case_id, id, recommendation, confidence,
                       cause_consistency, suggested_claim_cause, cause_evidence
                  FROM llm_analysis
                 WHERE case_id IN (:caseIds)
                 ORDER BY case_id, id DESC
                """, params, (rs, rowNum) -> {
            // numeric(5,3): the Postgres driver won't convert it to Double via getObject(name, Class).
            BigDecimal rawConfidence = rs.getBigDecimal("confidence");
            Double confidence = rawConfidence == null ? null : rawConfidence.doubleValue();
            String consistency = rs.getString("cause_consistency");
            return new LatestAnalysis(
                    rs.getLong("case_id"),
                    rs.getLong("id"),
                    Classification.valueOf(rs.getString("recommendation")),
                    confidence,
                    consistency == null ? null : CauseConsistency.valueOf(consistency),
                    rs.getString("suggested_claim_cause"),
                    rs.getString("cause_evidence"));
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
                    breakdownByCase.get(caseId),
                    row == null ? null : row.causeConsistency(),
                    row == null ? null : row.suggestedClaimCause(),
                    row == null ? null : row.causeEvidence()));
        }
        return result;
    }

    private record LatestAnalysis(Long caseId, Long analysisId, Classification classification,
                                  Double confidence, CauseConsistency causeConsistency,
                                  String suggestedClaimCause, String causeEvidence) {
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
