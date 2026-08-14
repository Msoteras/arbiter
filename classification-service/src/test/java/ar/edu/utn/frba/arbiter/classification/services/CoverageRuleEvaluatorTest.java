package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.RuleFinding;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test of the hard coverage rule evaluator (D3): no Spring, no database. Seed ids:
 * cobertura 1 = "Robo de celular", claim_cause 3 = "Hurto".
 */
class CoverageRuleEvaluatorTest {

    private final CoverageRuleEvaluator evaluator = new CoverageRuleEvaluator();

    private static final BusinessRules.EvaluableRule EXCLUDE_HURTO = BusinessRules.EvaluableRule.builder()
            .id(3L)
            .ruleType("COVERAGE_EXCLUSION")
            .effect("RECHAZAR")
            .blocksFastTrack(true)
            .excludedClaimCauseIds(List.of(3L))
            .build();

    private ClaimReport claim(String claimCause, Long claimCauseId) {
        return ClaimReport.builder()
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause(claimCause)
                .coverageId(1L)
                .claimCauseId(claimCauseId)
                .insuredId("40.123.456")
                .policyNumber("POL-CEL-2024-001")
                .description("...")
                .build();
    }

    private BusinessRules rulesWith(List<BusinessRules.EvaluableRule> evaluableRules) {
        return BusinessRules.builder()
                .branchId("Celulares")
                .claimCauseId("Robo en vía pública")
                .rules(List.of())
                .exclusions(List.of())
                .fastTrackCriteria(List.of())
                .evaluableRules(evaluableRules)
                .build();
    }

    @Test
    void excludedCause_isExcluded_andRecordsFail() {
        CoverageRuleEvaluator.Result result =
                evaluator.evaluate(claim("Hurto", 3L), rulesWith(List.of(EXCLUDE_HURTO)));

        assertThat(result.excluded()).isTrue();
        assertThat(result.findings()).hasSize(1);
        RuleFinding finding = result.findings().get(0);
        assertThat(finding.passed()).isFalse();
        assertThat(finding.result()).isEqualTo("FAIL");
        assertThat(finding.ruleId()).isEqualTo(3L);
        assertThat(finding.ruleType()).isEqualTo("COVERAGE_EXCLUSION");
        assertThat(finding.evaluatedValue()).contains("id=3");
    }

    @Test
    void nonExcludedCause_isNotExcluded_andRecordsPass() {
        // Un robo (claim_cause 2) sobre la misma cobertura no está en la lista negra.
        CoverageRuleEvaluator.Result result =
                evaluator.evaluate(claim("Robo en vía pública", 2L), rulesWith(List.of(EXCLUDE_HURTO)));

        assertThat(result.excluded()).isFalse();
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).passed()).isTrue();
        assertThat(result.findings().get(0).result()).isEqualTo("PASS");
    }

    @Test
    void noEvaluableRules_isBaseline_noFindings() {
        assertThat(evaluator.evaluate(claim("Hurto", 3L), rulesWith(List.of())).excluded()).isFalse();
        assertThat(evaluator.evaluate(claim("Hurto", 3L), rulesWith(List.of())).findings()).isEmpty();
    }

    @Test
    void nullEvaluableRules_isBaseline_noFindings() {
        CoverageRuleEvaluator.Result result = evaluator.evaluate(claim("Hurto", 3L), rulesWith(null));
        assertThat(result.excluded()).isFalse();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void nullClaimCauseId_cannotMatch_recordsPass() {
        // The isolated flow (no case) may not carry the id: it can't exclude blindly.
        CoverageRuleEvaluator.Result result =
                evaluator.evaluate(claim("Hurto", null), rulesWith(List.of(EXCLUDE_HURTO)));

        assertThat(result.excluded()).isFalse();
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).passed()).isTrue();
    }

    @Test
    void otherRuleType_isIgnored() {
        BusinessRules.EvaluableRule notAnExclusion = BusinessRules.EvaluableRule.builder()
                .id(9L)
                .ruleType("SOME_OTHER_TYPE")
                .excludedClaimCauseIds(List.of(3L))
                .build();

        CoverageRuleEvaluator.Result result =
                evaluator.evaluate(claim("Hurto", 3L), rulesWith(List.of(notAnExclusion)));

        assertThat(result.excluded()).isFalse();
        assertThat(result.findings()).isEmpty();
    }
}
