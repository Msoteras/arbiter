package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction.AffectedParty;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
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

    /** Las dos coberturas de una póliza de celulares: cada una con su propia suma asegurada. */
    private static final String ROBO = "Robo de celular";
    private static final String HURTO = "Hurto";

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

    private ClaimReport claim(BigDecimal claimedAmount) {
        return ClaimReport.builder()
                .branch("Celulares")
                .claimCause("Robo en vía pública")
                .coverageName(ROBO)
                .insuredId("40.123.456")
                .policyNumber(POLICY)
                .description("...")
                .eventDate(LocalDateTime.of(2026, 6, 13, 20, 0))
                .claimedAmount(claimedAmount)
                .build();
    }

    private InsuredPolicy policy(BigDecimal insuredAmount) {
        return InsuredPolicy.builder().policyNumber(POLICY).insuredAmount(insuredAmount).build();
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
        return priorClaim(policyNumber, status, null);
    }

    private InsuredHistory.ClaimRecord priorClaim(
            String policyNumber, String status, BigDecimal amountSettled) {
        return priorClaim(policyNumber, status, amountSettled, ROBO);
    }

    private InsuredHistory.ClaimRecord priorClaim(
            String policyNumber, String status, BigDecimal amountSettled, String coverageName) {
        return InsuredHistory.ClaimRecord.builder()
                .claimId("H-1")
                .date(LocalDate.of(2025, 8, 1))
                .policyNumber(policyNumber)
                .branch("Celulares")
                .coverageName(coverageName)
                .status(status)
                .amountSettled(amountSettled)
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
                claim(), policy(null), history(), rules(false, null), documentSaying(AffectedParty.FAMILIAR));

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("grupo familiar"));
    }

    @Test
    void aFamilyMemberOnACoverageThatIncludesThem_doesNotBlock() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(null), history(), rules(true, null), documentSaying(AffectedParty.FAMILIAR));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    @Test
    void theTitularIsNeverAFamilyGroupProblem() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(null), history(), rules(false, null), documentSaying(AffectedParty.TITULAR));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /**
     * The most important one: if the document doesn't say whose device it was, the rule sits out.
     * Que el papel no lo diga no puede costarle la cobertura a nadie.
     */
    @Test
    void anUnknownAffectedParty_doesNotBlock() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(null), history(), rules(false, null), documentSaying(AffectedParty.DESCONOCIDO));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** With no documents read there's no data either: the rule can't be evaluated. */
    @Test
    void withoutDocuments_theFamilyGroupRuleDoesNotParticipate() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(null), history(), rules(false, null), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** With the column unconfigured the rule doesn't exist for that coverage. */
    @Test
    void withoutTheColumnConfigured_theRuleDoesNotParticipate() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(null), history(), rules(null, null), documentSaying(AffectedParty.FAMILIAR));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    // ─── claim_exhausts_coverage ──────────────────────────────────────────────────

    @Test
    void aSettledPriorClaimOnTheSamePolicy_exhaustsTheCoverage() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(null), history(priorClaim(POLICY, "LIQUIDADO")), rules(null, true), Map.of());

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("ya fue consumida"));
    }

    /** Un siniestro rechazado no consumió nada. */
    @Test
    void aRejectedPriorClaim_doesNotExhaustTheCoverage() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(null), history(priorClaim(POLICY, "RECHAZADO")), rules(null, true), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** Coverage is exhausted per policy: a claim on another policy of the same insured doesn't count. */
    @Test
    void aSettledClaimOnAnotherPolicy_doesNotExhaustThisCoverage() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(null), history(priorClaim("POL-OTRA-999", "LIQUIDADO")), rules(null, true), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    @Test
    void aCoverageThatDoesNotExhaust_neverBlocks() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(null), history(priorClaim(POLICY, "LIQUIDADO")), rules(null, false), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    // ─── suma asegurada (monto acumulado) ──────────────────────────────────────────

    @Test
    void settledClaimsPlusThisOne_exceedingTheInsuredAmount_blocks() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(new BigDecimal("60000")), policy(new BigDecimal("100000")),
                history(priorClaim(POLICY, "LIQUIDADO", new BigDecimal("50000"))),
                rules(null, false), Map.of());

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("suma asegurada"));
    }

    @Test
    void settledClaimsPlusThisOne_withinTheInsuredAmount_doesNotBlock() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(new BigDecimal("30000")), policy(new BigDecimal("100000")),
                history(priorClaim(POLICY, "LIQUIDADO", new BigDecimal("50000"))),
                rules(null, false), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /**
     * El motivo del cambio: la suma asegurada es de la COBERTURA y no hay tope agregado de póliza
     * (confirmado con la analista, 01/09/2026). Un robo liquidado no consume nada del techo de
     * hurto. Antes se sumaba todo lo liquidado de la póliza contra el techo de una sola cobertura,
     * y esto reportaba la cobertura agotada sin que se hubiera denunciado un solo hurto.
     */
    @Test
    void settledClaimsOnAnotherCoverage_doNotConsumeThisOne() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(new BigDecimal("60000")), policy(new BigDecimal("100000")),
                history(priorClaim(POLICY, "LIQUIDADO", new BigDecimal("90000"), HURTO)),
                rules(null, false), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /**
     * Un previo sin cobertura imputada por la compañía se saltea en vez de cargarlo contra la
     * cobertura equivocada: la regla solo bloquea Fast Track y le da un motivo al analista, y un
     * motivo falso es peor que uno que falta.
     */
    @Test
    void priorClaimsWithNoCoverageOnRecord_areLeftOut() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(new BigDecimal("60000")), policy(new BigDecimal("100000")),
                history(priorClaim(POLICY, "LIQUIDADO", new BigDecimal("90000"), null)),
                rules(null, false), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** Un siniestro rechazado no consumió nada de la suma asegurada. */
    @Test
    void rejectedPriorClaims_doNotCountTowardTheInsuredAmount() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(new BigDecimal("60000")), policy(new BigDecimal("100000")),
                history(priorClaim(POLICY, "RECHAZADO", new BigDecimal("90000"))),
                rules(null, false), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** La suma asegurada es por póliza: lo liquidado en otra póliza no cuenta acá. */
    @Test
    void settledClaimsOnAnotherPolicy_doNotCountTowardThisInsuredAmount() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(new BigDecimal("60000")), policy(new BigDecimal("100000")),
                history(priorClaim("POL-OTRA-999", "LIQUIDADO", new BigDecimal("90000"))),
                rules(null, false), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** Sin `insuredAmount` no hay contra qué comparar: la regla no participa. */
    @Test
    void withoutInsuredAmount_theRuleDoesNotParticipate() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(new BigDecimal("999999")), policy(null),
                history(priorClaim(POLICY, "LIQUIDADO", new BigDecimal("999999"))),
                rules(null, false), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** Sin `claimedAmount` en el reclamo actual, tampoco hay nada que sumar todavía. */
    @Test
    void withoutClaimedAmount_theRuleDoesNotParticipate() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(new BigDecimal("100000")),
                history(priorClaim(POLICY, "LIQUIDADO", new BigDecimal("999999"))),
                rules(null, false), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    // ─── rastro auditable (SSN 2/2023) ────────────────────────────────────────────
    // Las dos reglas frenaban el Fast Track sin dejar fila en rule_result, así que el analista
    // veía el motivo en prosa pero no la regla. Van sin ruleId a propósito: son columnas de
    // `coverage`, no filas de `insurer_rule`.

    @Test
    void anExhaustedCoverage_leavesAnAuditableFailure() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(null), history(priorClaim(POLICY, "LIQUIDADO")), rules(null, true), Map.of());

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
                claim(), policy(null), history(priorClaim(POLICY, "RECHAZADO")), rules(null, true), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
        assertThat(result.findings()).singleElement()
                .extracting(RuleFinding::ruleType, RuleFinding::result)
                .containsExactly("CLAIM_EXHAUSTS_COVERAGE", "PASS");
    }

    @Test
    void aFamilyMemberOnACoverageThatExcludesThem_leavesAnAuditableFailure() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(null), history(), rules(false, null), documentSaying(AffectedParty.FAMILIAR));

        assertThat(result.findings()).singleElement()
                .extracting(RuleFinding::ruleType, RuleFinding::result, RuleFinding::evaluatedValue)
                .containsExactly("COVERS_FAMILY_GROUP", "FAIL", "affectedParty=FAMILIAR");
    }

    @Test
    void aHolderOnACoverageThatExcludesTheFamily_leavesAnAuditablePass() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(null), history(), rules(false, null), documentSaying(AffectedParty.TITULAR));

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
                claim(), policy(null), history(), rules(false, null), documentSaying(AffectedParty.DESCONOCIDO));

        assertThat(result.blocksFastTrack()).isFalse();
        assertThat(result.findings()).isEmpty();
    }

    /** Regla apagada, regla que no se evalúa: tampoco deja rastro. */
    @Test
    void rulesTurnedOff_leaveNoTrace() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(null), history(priorClaim(POLICY, "LIQUIDADO")), rules(null, null),
                documentSaying(AffectedParty.FAMILIAR));

        assertThat(result.findings()).isEmpty();
    }
}
