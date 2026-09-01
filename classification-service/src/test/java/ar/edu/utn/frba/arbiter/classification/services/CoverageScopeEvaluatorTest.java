package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction.AffectedParty;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.RuleFinding;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D9 · alcance de la cobertura: a quién alcanza ({@code covers_family_group}) y si le queda saldo
 * ({@code claim_exhausts_coverage}). What matters most to test is when the rule does <b>not</b>
 * fire: these are rules that can cost someone their coverage.
 */
class CoverageScopeEvaluatorTest {

    private static final String POLICY = "POL-CEL-2024-001";

    private final CoverageScopeEvaluator evaluator = new CoverageScopeEvaluator();

    private ClaimReport claim() {
        return ClaimReport.builder()
                .branch("Celulares")
                .claimCause("Robo en vía pública")
                .insuredId("40.123.456")
                .policyNumber(POLICY)
                .description("...")
                .eventDate(LocalDateTime.of(2026, 6, 13, 20, 0))
                .build();
    }

    private BusinessRules rules(Boolean coversFamilyGroup, Boolean claimExhaustsCoverage) {
        return BusinessRules.builder()
                .branchId("Celulares")
                .rules(List.of()).exclusions(List.of()).fastTrackCriteria(List.of())
                .coversFamilyGroup(coversFamilyGroup)
                .claimExhaustsCoverage(claimExhaustsCoverage)
                .build();
    }

    private InsuredHistory history(InsuredHistory.ClaimRecord... claims) {
        return InsuredHistory.builder()
                .insuredId("40.123.456")
                .previousClaimsCount(claims.length)
                .totalAmountClaimed(BigDecimal.ZERO)
                .claims(List.of(claims))
                .build();
    }

    private InsuredHistory.ClaimRecord priorClaim(String policyNumber, String status) {
        return InsuredHistory.ClaimRecord.builder()
                .claimId("H-1")
                .date(LocalDate.of(2025, 8, 1))
                .policyNumber(policyNumber)
                .branch("Celulares")
                .status(status)
                .build();
    }

    private Map<String, DocumentExtraction> documentSaying(AffectedParty affectedParty) {
        return Map.of("police_report", new DocumentExtraction(
                "constancia", List.of(),
                new DocumentExtraction.Fields(null, null, null, null, affectedParty)));
    }

    // ─── covers_family_group ──────────────────────────────────────────────────────

    @Test
    void aFamilyMemberOnACoverageThatExcludesThem_blocksFastTrack() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(), rules(false, null), documentSaying(AffectedParty.FAMILIAR));

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("grupo familiar"));
    }

    @Test
    void aFamilyMemberOnACoverageThatIncludesThem_doesNotBlock() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(), rules(true, null), documentSaying(AffectedParty.FAMILIAR));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    @Test
    void theTitularIsNeverAFamilyGroupProblem() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(), rules(false, null), documentSaying(AffectedParty.TITULAR));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /**
     * The most important one: if the document doesn't say whose device it was, the rule sits out.
     * Que el papel no lo diga no puede costarle la cobertura a nadie.
     */
    @Test
    void anUnknownAffectedParty_doesNotBlock() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(), rules(false, null), documentSaying(AffectedParty.DESCONOCIDO));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** With no documents read there's no data either: the rule can't be evaluated. */
    @Test
    void withoutDocuments_theFamilyGroupRuleDoesNotParticipate() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(), rules(false, null), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** With the column unconfigured the rule doesn't exist for that coverage. */
    @Test
    void withoutTheColumnConfigured_theRuleDoesNotParticipate() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(), rules(null, null), documentSaying(AffectedParty.FAMILIAR));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    // ─── claim_exhausts_coverage ──────────────────────────────────────────────────

    @Test
    void aSettledPriorClaimOnTheSamePolicy_exhaustsTheCoverage() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(priorClaim(POLICY, "LIQUIDADO")), rules(null, true), Map.of());

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("ya fue consumida"));
    }

    /** Un siniestro rechazado no consumió nada. */
    @Test
    void aRejectedPriorClaim_doesNotExhaustTheCoverage() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(priorClaim(POLICY, "RECHAZADO")), rules(null, true), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** Coverage is exhausted per policy: a claim on another policy of the same insured doesn't count. */
    @Test
    void aSettledClaimOnAnotherPolicy_doesNotExhaustThisCoverage() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(priorClaim("POL-OTRA-999", "LIQUIDADO")), rules(null, true), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    @Test
    void aCoverageThatDoesNotExhaust_neverBlocks() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(priorClaim(POLICY, "LIQUIDADO")), rules(null, false), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    // ─── rastro auditable (SSN 2/2023) ────────────────────────────────────────────
    // Las dos reglas frenaban el Fast Track sin dejar fila en rule_result, así que el analista
    // veía el motivo en prosa pero no la regla. Van sin ruleId a propósito: son columnas de
    // `coverage`, no filas de `insurer_rule`.

    @Test
    void anExhaustedCoverage_leavesAnAuditableFailure() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(priorClaim(POLICY, "LIQUIDADO")), rules(null, true), Map.of());

        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.ruleType()).isEqualTo("CLAIM_EXHAUSTS_COVERAGE");
            assertThat(f.result()).isEqualTo("FAIL");
            assertThat(f.evaluatedValue()).isEqualTo("settledClaimsOnPolicy=1 max=0");
            assertThat(f.ruleId()).isNull();
        });
    }

    /** El punto de la historia: que se vean las que pasaron, no solo las que fallaron. */
    @Test
    void aCoverageWithBalanceLeft_leavesAnAuditablePass() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(priorClaim(POLICY, "RECHAZADO")), rules(null, true), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
        assertThat(result.findings()).singleElement()
                .extracting(RuleFinding::ruleType, RuleFinding::result)
                .containsExactly("CLAIM_EXHAUSTS_COVERAGE", "PASS");
    }

    @Test
    void aFamilyMemberOnACoverageThatExcludesThem_leavesAnAuditableFailure() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(), rules(false, null), documentSaying(AffectedParty.FAMILIAR));

        assertThat(result.findings()).singleElement()
                .extracting(RuleFinding::ruleType, RuleFinding::result, RuleFinding::evaluatedValue)
                .containsExactly("COVERS_FAMILY_GROUP", "FAIL", "affectedParty=FAMILIAR");
    }

    @Test
    void aHolderOnACoverageThatExcludesTheFamily_leavesAnAuditablePass() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(), rules(false, null), documentSaying(AffectedParty.TITULAR));

        assertThat(result.findings()).singleElement()
                .extracting(RuleFinding::ruleType, RuleFinding::result, RuleFinding::evaluatedValue)
                .containsExactly("COVERS_FAMILY_GROUP", "PASS", "affectedParty=TITULAR");
    }

    /**
     * Sin evaluar es un tercer estado y no escribe fila: que ningún papel diga de quién era el
     * equipo no puede contar ni a favor ni en contra. Un PASS acá diría que se verificó algo que
     * nadie verificó.
     */
    @Test
    void anUnknownInjuredParty_leavesNoTraceAtAll() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(), rules(false, null), documentSaying(AffectedParty.DESCONOCIDO));

        assertThat(result.blocksFastTrack()).isFalse();
        assertThat(result.findings()).isEmpty();
    }

    /** Regla apagada, regla que no se evalúa: tampoco deja rastro. */
    @Test
    void rulesTurnedOff_leaveNoTrace() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(priorClaim(POLICY, "LIQUIDADO")), rules(null, null),
                documentSaying(AffectedParty.FAMILIAR));

        assertThat(result.findings()).isEmpty();
    }
}
