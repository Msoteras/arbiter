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

/** What matters most is when these rules do <b>not</b> fire: they can cost someone their coverage. */
class CoverageScopeEvaluatorTest {

    private static final String POLICY = "POL-CEL-2024-001";

    /** A cellphone policy's two coverages, each with its own sum insured. */
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
                new DocumentExtraction.Fields(
                        null, null, null, null, null, null, affectedParty, null, List.of())));
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
    void thePolicyholderIsNeverAFamilyGroupProblem() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(null), history(), rules(false, null), documentSaying(AffectedParty.TITULAR));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** The most important one: if no document says whose device it was, the rule sits out. */
    @Test
    void anUnknownAffectedParty_doesNotBlock() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(null), history(), rules(false, null), documentSaying(AffectedParty.DESCONOCIDO));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    @Test
    void withoutDocuments_theFamilyGroupRuleDoesNotParticipate() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(null), history(), rules(false, null), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

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

    /** A rejected claim consumed nothing. */
    @Test
    void aRejectedPriorClaim_doesNotExhaustTheCoverage() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(null), history(priorClaim(POLICY, "RECHAZADO")), rules(null, true), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

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

    // ─── sum insured (accumulated amount) ─────────────────────────────────────────

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

    /** The sum insured is per coverage (no policy-wide ceiling): a settled robbery doesn't consume the theft one. */
    @Test
    void settledClaimsOnAnotherCoverage_doNotConsumeThisOne() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(new BigDecimal("60000")), policy(new BigDecimal("100000")),
                history(priorClaim(POLICY, "LIQUIDADO", new BigDecimal("90000"), HURTO)),
                rules(null, false), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** A prior claim with no recorded coverage is skipped: a false reason is worse than a missing one. */
    @Test
    void priorClaimsWithNoCoverageOnRecord_areLeftOut() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(new BigDecimal("60000")), policy(new BigDecimal("100000")),
                history(priorClaim(POLICY, "LIQUIDADO", new BigDecimal("90000"), null)),
                rules(null, false), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** A rejected claim consumed none of the sum insured. */
    @Test
    void rejectedPriorClaims_doNotCountTowardTheInsuredAmount() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(new BigDecimal("60000")), policy(new BigDecimal("100000")),
                history(priorClaim(POLICY, "RECHAZADO", new BigDecimal("90000"))),
                rules(null, false), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** What was settled on another policy doesn't count here. */
    @Test
    void settledClaimsOnAnotherPolicy_doNotCountTowardThisInsuredAmount() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(new BigDecimal("60000")), policy(new BigDecimal("100000")),
                history(priorClaim("POL-OTRA-999", "LIQUIDADO", new BigDecimal("90000"))),
                rules(null, false), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** Without `insuredAmount` there's nothing to compare against: the rule doesn't take part. */
    @Test
    void withoutInsuredAmount_theRuleDoesNotParticipate() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(new BigDecimal("999999")), policy(null),
                history(priorClaim(POLICY, "LIQUIDADO", new BigDecimal("999999"))),
                rules(null, false), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** Without the current `claimedAmount` there's nothing to add either. */
    @Test
    void withoutClaimedAmount_theRuleDoesNotParticipate() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(new BigDecimal("100000")),
                history(priorClaim(POLICY, "LIQUIDADO", new BigDecimal("999999"))),
                rules(null, false), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    // ─── audit trail ──────────────────────────────────────────────────────────────
    // No ruleId on purpose: these are `coverage` columns, not `insurer_rule` rows.

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

    /** Passes are recorded too, not only failures. */
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

    /** Unevaluated is a third state and writes no row: a PASS would claim something was verified. */
    @Test
    void anUnknownInjuredParty_leavesNoTraceAtAll() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(null), history(), rules(false, null), documentSaying(AffectedParty.DESCONOCIDO));

        assertThat(result.blocksFastTrack()).isFalse();
        assertThat(result.findings()).isEmpty();
    }

    /** A disabled rule leaves no row either. */
    @Test
    void rulesTurnedOff_leaveNoTrace() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), policy(null), history(priorClaim(POLICY, "LIQUIDADO")), rules(null, null),
                documentSaying(AffectedParty.FAMILIAR));

        assertThat(result.findings()).isEmpty();
    }
}
