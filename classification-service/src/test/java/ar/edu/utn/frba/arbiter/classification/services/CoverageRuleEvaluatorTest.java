package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.RuleFinding;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit puro del evaluador de reglas duras de cobertura (D3): no levanta Spring ni base. Ids del seed:
 * cobertura 1 = "Robo de celular", claim_cause 2 = "Robo en vía pública", claim_cause 3 = "Hurto".
 */
class CoverageRuleEvaluatorTest {

    private final CoverageRuleEvaluator evaluator = new CoverageRuleEvaluator();

    private static final BusinessRules.EvaluableRule INCLUDE_ROBO = BusinessRules.EvaluableRule.builder()
            .id(3L)
            .ruleType("COVERAGE_INCLUSION")
            .effect("RECHAZAR")
            .blocksFastTrack(true)
            .includedClaimCauseIds(List.of(2L))
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
    void includedCause_isCovered_andRecordsPass() {
        CoverageRuleEvaluator.Result result =
                evaluator.evaluate(claim("Robo en vía pública", 2L), rulesWith(List.of(INCLUDE_ROBO)));

        assertThat(result.notCovered()).isFalse();
        assertThat(result.findings()).hasSize(1);
        RuleFinding finding = result.findings().get(0);
        assertThat(finding.passed()).isTrue();
        assertThat(finding.result()).isEqualTo("PASS");
        assertThat(finding.ruleId()).isEqualTo(3L);
        assertThat(finding.ruleType()).isEqualTo("COVERAGE_INCLUSION");
        assertThat(finding.evaluatedValue()).contains("id=2");
    }

    @Test
    void nonIncludedCause_isNotCovered_andRecordsFail() {
        // Un hurto (claim_cause 3) sobre la cobertura de robo no está en la lista de cubiertos.
        CoverageRuleEvaluator.Result result =
                evaluator.evaluate(claim("Hurto", 3L), rulesWith(List.of(INCLUDE_ROBO)));

        assertThat(result.notCovered()).isTrue();
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).passed()).isFalse();
        assertThat(result.findings().get(0).result()).isEqualTo("FAIL");
    }

    @Test
    void noEvaluableRules_isBaseline_noFindings() {
        // Sin ninguna regla configurada para la cobertura, el motor no tiene nada que evaluar y cae
        // al baseline (no bloquea) — distinto de "hay regla pero la lista está vacía", que sí bloquea.
        assertThat(evaluator.evaluate(claim("Hurto", 3L), rulesWith(List.of())).notCovered()).isFalse();
        assertThat(evaluator.evaluate(claim("Hurto", 3L), rulesWith(List.of())).findings()).isEmpty();
    }

    @Test
    void nullEvaluableRules_isBaseline_noFindings() {
        CoverageRuleEvaluator.Result result = evaluator.evaluate(claim("Hurto", 3L), rulesWith(null));
        assertThat(result.notCovered()).isFalse();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void emptyIncludedList_coversNothing_recordsFail() {
        // El referente puede dejar la lista vacía desde la UI: a diferencia de la exclusión vieja,
        // acá SÍ importa — una regla configurada sin nada adentro no cubre nada.
        BusinessRules.EvaluableRule includesNothing = BusinessRules.EvaluableRule.builder()
                .id(5L)
                .ruleType("COVERAGE_INCLUSION")
                .includedClaimCauseIds(List.of())
                .build();

        CoverageRuleEvaluator.Result result =
                evaluator.evaluate(claim("Robo en vía pública", 2L), rulesWith(List.of(includesNothing)));

        assertThat(result.notCovered()).isTrue();
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).passed()).isFalse();
    }

    @Test
    void nullClaimCauseId_cannotConfirmCoverage_recordsFail() {
        // El flujo aislado (sin expediente) puede no traer el id: sin poder confirmar que está
        // cubierto, el default es fail-closed (no cubierto), no fail-open como antes.
        CoverageRuleEvaluator.Result result =
                evaluator.evaluate(claim("Robo en vía pública", null), rulesWith(List.of(INCLUDE_ROBO)));

        assertThat(result.notCovered()).isTrue();
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).passed()).isFalse();
    }

    @Test
    void otherRuleType_isIgnored() {
        BusinessRules.EvaluableRule notAnInclusion = BusinessRules.EvaluableRule.builder()
                .id(9L)
                .ruleType("SOME_OTHER_TYPE")
                .includedClaimCauseIds(List.of(3L))
                .build();

        CoverageRuleEvaluator.Result result =
                evaluator.evaluate(claim("Hurto", 3L), rulesWith(List.of(notAnInclusion)));

        assertThat(result.notCovered()).isFalse();
        assertThat(result.findings()).isEmpty();
    }
}
