package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.classification.dto.RuleFinding;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hard temporal rules (D13 coverage window, D9 waiting period, D11 report deadline, D12
 * police-report deadline, D10 events cap, arrears). Plain unit test.
 *
 * <p>Each rule needs <b>two</b> things to participate: the insurer has it active (an
 * {@code insurer_rule} row, arriving here as {@code evaluableRules}) and the data to evaluate it.
 * The tests cover the two absences separately, because they mean different things: "this insurer
 * doesn't apply the rule" vs. "there's nothing to evaluate it with".
 */
class TemporalRuleEvaluatorTest {

    private final TemporalRuleEvaluator evaluator = new TemporalRuleEvaluator();

    private static final LocalDateTime EVENT = LocalDateTime.of(2026, 6, 13, 20, 0);
    private static final long POLICE_DEADLINE_HOURS = 72L;

    // ─── Active rules (what rules-service serves) ──────────────────────────────

    private static BusinessRules.EvaluableRule active(long id, RuleType type) {
        return BusinessRules.EvaluableRule.builder()
                .id(id).ruleType(type.name()).effect("DERIVAR").blocksFastTrack(true).build();
    }

    private static BusinessRules.EvaluableRule activePoliceDeadline(long deadlineHours) {
        return BusinessRules.EvaluableRule.builder()
                .id(7L).ruleType(RuleType.POLICE_DEADLINE.name()).effect("DERIVAR")
                .blocksFastTrack(true).deadlineHours(deadlineHours).build();
    }

    /** The five temporal-window rules active, which is how the seed's insurers are left. Arrears
     *  (POLICY_STANDING) has its own dedicated section below since it seeds off by default. */
    private static List<BusinessRules.EvaluableRule> allActive() {
        return List.of(
                active(4L, RuleType.POLICY_IN_FORCE),
                active(5L, RuleType.WAITING_PERIOD),
                active(6L, RuleType.REPORT_DEADLINE),
                activePoliceDeadline(POLICE_DEADLINE_HOURS),
                active(8L, RuleType.MAX_EVENTS_YEAR));
    }

    // ─── D12 · police-report deadline ──────────────────────────────────────
    // The event is 2026-06-13 at 20:00.

    private ClaimReport claimWithPoliceReport(LocalDateTime policeReportAt) {
        return ClaimReport.builder()
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause("Robo en vía pública")
                .coverageId(1L)
                .claimCauseId(2L)
                .insuredId("40.123.456")
                .policyNumber("POL-CEL-2024-001")
                .description("...")
                .eventDate(EVENT)
                .policeReportAt(policeReportAt)
                .build();
    }

    @Test
    void aPoliceReportWithinTheDeadline_doesNotBlock() {
        TemporalRuleEvaluator.Result result = evaluator.evaluate(
                claimWithPoliceReport(EVENT.plusHours(30)),
                policy(LocalDate.of(2024, 3, 1), LocalDate.of(2027, 3, 1)),
                history(List.of()),
                rules(null, null));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    @Test
    void aPoliceReportPastTheDeadline_blocksFastTrack() {
        TemporalRuleEvaluator.Result result = evaluator.evaluate(
                claimWithPoliceReport(EVENT.plusHours(100)),
                policy(LocalDate.of(2024, 3, 1), LocalDate.of(2027, 3, 1)),
                history(List.of()),
                rules(null, null));

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("Police report past the deadline"));
    }

    /**
     * The threshold belongs to the insurer, not to the application: with 120h configured, the same
     * report that failed at 72 passes. That's what the hardcoded property never allowed.
     */
    @Test
    void theDeadlineComesFromTheInsurersRule_notFromAConstant() {
        TemporalRuleEvaluator.Result result = evaluator.evaluate(
                claimWithPoliceReport(EVENT.plusHours(100)),
                policy(LocalDate.of(2024, 3, 1), LocalDate.of(2027, 3, 1)),
                history(List.of()),
                rulesWith(List.of(activePoliceDeadline(120L))));

        assertThat(result.blocksFastTrack()).isFalse();
        assertThat(result.findings()).singleElement()
                .satisfies(f -> assertThat(f.result()).isEqualTo("PASS"));
    }

    /** Reporting to the police before the event is inconsistent data, not a deadline met. */
    @Test
    void aPoliceReportBeforeTheEvent_blocksFastTrack() {
        TemporalRuleEvaluator.Result result = evaluator.evaluate(
                claimWithPoliceReport(EVENT.minusDays(2)),
                policy(LocalDate.of(2024, 3, 1), LocalDate.of(2027, 3, 1)),
                history(List.of()),
                rules(null, null));

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("predates the event"));
    }

    /** "No police report was filed" is legitimate: the rule doesn't participate, doesn't block blindly. */
    @Test
    void withoutADeclaredPoliceReport_theRuleDoesNotParticipate() {
        TemporalRuleEvaluator.Result result = evaluator.evaluate(
                claimWithPoliceReport(null),
                policy(LocalDate.of(2024, 3, 1), LocalDate.of(2027, 3, 1)),
                history(List.of()),
                rules(null, null));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /**
     * A rule that's active but has no threshold loaded doesn't get evaluated. Not a hypothetical
     * case: the referente can turn the rule on and save before filling in the number.
     */
    @Test
    void anEnabledPoliceDeadlineWithoutThreshold_doesNotParticipate() {
        BusinessRules.EvaluableRule noThreshold = BusinessRules.EvaluableRule.builder()
                .id(7L).ruleType(RuleType.POLICE_DEADLINE.name()).blocksFastTrack(true).build();

        TemporalRuleEvaluator.Result result = evaluator.evaluate(
                claimWithPoliceReport(EVENT.plusHours(500)),
                policy(LocalDate.of(2024, 3, 1), LocalDate.of(2027, 3, 1)),
                history(List.of()),
                rulesWith(List.of(noThreshold)));

        assertThat(result.blocksFastTrack()).isFalse();
        assertThat(result.findings()).isEmpty();
    }

    private ClaimReport claim(LocalDateTime reportedAt) {
        return ClaimReport.builder()
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause("Robo en vía pública")
                .coverageId(1L)
                .claimCauseId(2L)
                .insuredId("40.123.456")
                .policyNumber("POL-CEL-2024-001")
                .description("...")
                .eventDate(EVENT)
                .reportedAt(reportedAt)
                .build();
    }

    private InsuredPolicy policy(LocalDate from, LocalDate to) {
        return policy(from, to, true);
    }

    private InsuredPolicy policy(LocalDate from, LocalDate to, boolean upToDate) {
        return InsuredPolicy.builder()
                .policyNumber("POL-CEL-2024-001")
                .effectiveFrom(from)
                .effectiveTo(to)
                .upToDate(upToDate)
                .insuredAmount(new BigDecimal("400000"))
                .build();
    }

    private InsuredHistory history(List<InsuredHistory.ClaimRecord> claims) {
        return InsuredHistory.builder()
                .insuredId("40.123.456")
                .previousClaimsCount(claims.size())
                .totalAmountClaimed(BigDecimal.ZERO)
                .claims(claims)
                .build();
    }

    private InsuredHistory.ClaimRecord priorClaim(LocalDate date, String branch) {
        return InsuredHistory.ClaimRecord.builder().claimId("x").date(date).branch(branch)
                .claimCause("Robo en vía pública").status("Aprobado").build();
    }

    /** All five active; the thresholds that belong to the coverage are passed as parameters. */
    private BusinessRules rules(Long reportDeadlineHours, Integer maxEventsPerYear) {
        return BusinessRules.builder()
                .branchId("Celulares")
                .rules(List.of()).exclusions(List.of()).fastTrackCriteria(List.of())
                .reportDeadlineHours(reportDeadlineHours)
                .maxEventsPerYear(maxEventsPerYear)
                .evaluableRules(allActive())
                .build();
    }

    private BusinessRules rulesWith(List<BusinessRules.EvaluableRule> evaluableRules) {
        return BusinessRules.builder()
                .branchId("Celulares")
                .rules(List.of()).exclusions(List.of()).fastTrackCriteria(List.of())
                .evaluableRules(evaluableRules)
                .build();
    }

    private BusinessRules rulesWithWaitingPeriod(Integer waitingPeriodDays) {
        return BusinessRules.builder()
                .branchId("Celulares")
                .rules(List.of()).exclusions(List.of()).fastTrackCriteria(List.of())
                .waitingPeriodDays(waitingPeriodDays)
                .evaluableRules(allActive())
                .build();
    }

    // ─── D9 · waiting period ────────────────────────────────────────────────────
    // The event is 2026-06-13. The waiting period is counted from the policy's start date.

    /** A 5-day-old policy with a 30-day waiting period: there's a contract, but no coverage yet. */
    @Test
    void eventInsideTheWaitingPeriod_blocksFastTrack() {
        TemporalRuleEvaluator.Result result = evaluator.evaluate(
                claim(EVENT.plusHours(2)),
                policy(LocalDate.of(2026, 6, 8), LocalDate.of(2027, 6, 8)),
                history(List.of()),
                rulesWithWaitingPeriod(30));

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("within the 30-day waiting period"));
    }

    /** Same case, one day after the waiting period ends: covered. */
    @Test
    void eventAfterTheWaitingPeriod_doesNotBlock() {
        TemporalRuleEvaluator.Result result = evaluator.evaluate(
                claim(EVENT.plusHours(2)),
                policy(LocalDate.of(2026, 5, 1), LocalDate.of(2027, 5, 1)),
                history(List.of()),
                rulesWithWaitingPeriod(30));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** The exact day the waiting period ends is already covered (start 05/14 + 30 days = 06/13). */
    @Test
    void theDayTheWaitingPeriodEnds_isAlreadyCovered() {
        TemporalRuleEvaluator.Result result = evaluator.evaluate(
                claim(EVENT.plusHours(2)),
                policy(LocalDate.of(2026, 5, 14), LocalDate.of(2027, 5, 14)),
                history(List.of()),
                rulesWithWaitingPeriod(30));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** No waiting period configured, the rule doesn't participate — doesn't block blindly. */
    @Test
    void withoutAWaitingPeriodConfigured_theRuleDoesNotParticipate() {
        TemporalRuleEvaluator.Result result = evaluator.evaluate(
                claim(EVENT.plusHours(2)),
                policy(LocalDate.of(2026, 6, 8), LocalDate.of(2027, 6, 8)),
                history(List.of()),
                rulesWithWaitingPeriod(null));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    // ── D13 · coverage window ─────────────────────────────────────────────────
    @Test
    void eventOutsidePolicyPeriod_blocks() {
        var result = evaluator.evaluate(
                claim(null), policy(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 6, 1)), history(List.of()), rules(null, null));

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("coverage window"));
    }

    @Test
    void eventWithinPolicyPeriod_ok() {
        var result = evaluator.evaluate(
                claim(null), policy(LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1)), history(List.of()), rules(null, null));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    // ── D11 · report deadline ─────────────────────────────────────────────────
    @Test
    void reportedLate_blocks() {
        // Reported 100h after the event, the coverage's deadline is 72h.
        var result = evaluator.evaluate(
                claim(EVENT.plusHours(100)), policy(LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1)),
                history(List.of()), rules(72L, null));

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("past the deadline"));
    }

    @Test
    void reportedInTime_ok() {
        var result = evaluator.evaluate(
                claim(EVENT.plusHours(10)), policy(LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1)),
                history(List.of()), rules(72L, null));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    @Test
    void reportBeforeEvent_blocksAsInconsistent() {
        var result = evaluator.evaluate(
                claim(EVENT.minusHours(5)), policy(LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1)),
                history(List.of()), rules(72L, null));

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("predates"));
    }

    // ── D10 · events-per-year cap ───────────────────────────────────────────
    @Test
    void exceedsMaxAnnualEvents_blocks() {
        // Cap of 1/year, already 1 claim in the branch over the trailing 12 months → this one is #2.
        var result = evaluator.evaluate(
                claim(null), policy(LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1)),
                history(List.of(priorClaim(LocalDate.of(2026, 2, 1), "Celulares"))), rules(null, 1));

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("cap"));
    }

    @Test
    void withinMaxAnnualEvents_ok() {
        var result = evaluator.evaluate(
                claim(null), policy(LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1)),
                history(List.of(priorClaim(LocalDate.of(2026, 2, 1), "Celulares"))), rules(null, 2));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    @Test
    void priorClaimOutsideWindowOrOtherBranch_doesNotCount() {
        var result = evaluator.evaluate(
                claim(null), policy(LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1)),
                history(List.of(
                        priorClaim(LocalDate.of(2024, 1, 1), "Celulares"), // outside the 12-month window
                        priorClaim(LocalDate.of(2026, 3, 1), "Hogar"))),    // other branch
                rules(null, 1));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    @Test
    void missingData_doesNotBlind_block() {
        // No policy dates, no reportedAt, and no configured limits: nothing to evaluate.
        var result = evaluator.evaluate(claim(null), policy(null, null), history(List.of()), rules(null, null));

        assertThat(result.blocksFastTrack()).isFalse();
        assertThat(result.reasons()).isEmpty();
        assertThat(result.findings()).isEmpty();
    }

    // ── Arrears (mora) ────────────────────────────────────────────────────────
    // No threshold of its own — the source fact is InsuredPolicy.upToDate().

    @Test
    void policyUpToDate_passes() {
        var result = evaluator.evaluate(
                claim(null),
                policy(LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1), true),
                history(List.of()),
                rulesWith(List.of(active(14L, RuleType.POLICY_STANDING))));

        assertThat(result.blocksFastTrack()).isFalse();
        assertThat(result.findings()).singleElement()
                .satisfies(f -> assertThat(f.result()).isEqualTo("PASS"));
    }

    @Test
    void policyInArrears_blocksFastTrackAndLeavesAFinding() {
        var result = evaluator.evaluate(
                claim(null),
                policy(LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1), false),
                history(List.of()),
                rulesWith(List.of(active(14L, RuleType.POLICY_STANDING))));

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("outstanding balance"));
        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.result()).isEqualTo("FAIL");
            assertThat(f.ruleId()).isEqualTo(14L);
        });
    }

    /** New behavior: an insurer that never turned the rule on isn't affected by a policy in arrears. */
    @Test
    void withoutTheRuleActive_arrearsDoNotParticipate() {
        var result = evaluator.evaluate(
                claim(null),
                policy(LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1), false),
                history(List.of()),
                rules(null, null)); // allActive() doesn't include POLICY_STANDING

        assertThat(result.findings()).noneMatch(f -> f.ruleType().equals(RuleType.POLICY_STANDING.name()));
    }

    // ── Per-insurer activation ─────────────────────────────────────────────

    /**
     * An insurer with none of the rules configured behaves as before they existed: the engine
     * doesn't evaluate them. This is the criterion that makes adding hard rules additive — a
     * company that didn't turn them on sees nothing change.
     */
    @Test
    void withoutConfiguredRules_nothingIsEvaluated() {
        BusinessRules noRules = BusinessRules.builder()
                .branchId("Celulares")
                .rules(List.of()).exclusions(List.of()).fastTrackCriteria(List.of())
                .reportDeadlineHours(72L)
                .maxEventsPerYear(1)
                .waitingPeriodDays(30)
                .build();

        var result = evaluator.evaluate(
                claim(EVENT.plusHours(500)), // far past any deadline
                policy(LocalDate.of(2026, 6, 12), LocalDate.of(2026, 6, 20)),
                history(List.of(priorClaim(LocalDate.of(2026, 2, 1), "Celulares"))),
                noRules);

        assertThat(result.blocksFastTrack()).isFalse();
        assertThat(result.reasons()).isEmpty();
        assertThat(result.findings()).isEmpty();
    }

    /** Turning one rule off doesn't turn off the others: the referente flips them one at a time. */
    @Test
    void onlyTheEnabledRulesAreEvaluated() {
        var result = evaluator.evaluate(
                claim(EVENT.plusHours(500)),
                policy(LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1)),
                history(List.of()),
                BusinessRules.builder()
                        .branchId("Celulares")
                        .rules(List.of()).exclusions(List.of()).fastTrackCriteria(List.of())
                        .reportDeadlineHours(72L)
                        .evaluableRules(List.of(active(4L, RuleType.POLICY_IN_FORCE)))
                        .build());

        // Only the coverage-window rule ran (and it passes); the report deadline was off despite having a threshold.
        assertThat(result.findings()).singleElement()
                .satisfies(f -> assertThat(f.ruleType()).isEqualTo(RuleType.POLICY_IN_FORCE.name()));
        assertThat(result.blocksFastTrack()).isFalse();
    }

    // ── Audit trail ────────────────────────────────────────────────────────

    /**
     * Disposición SSN 2/2023's audit trail is "which rule was evaluated and with what result", not
     * "which rule failed": a table with only rejections doesn't prove the others ran.
     */
    @Test
    void everyEvaluatedRuleLeavesAFinding_passAndFail() {
        var result = evaluator.evaluate(
                claim(EVENT.plusHours(100)), // past the 72h deadline → FAIL
                policy(LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1)), // in force → PASS
                history(List.of()),
                rules(72L, 2)); // cap 2, no prior claims → PASS

        assertThat(result.findings()).hasSize(3);
        assertThat(result.findings())
                .filteredOn(f -> f.ruleType().equals(RuleType.REPORT_DEADLINE.name()))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.result()).isEqualTo("FAIL");
                    assertThat(f.ruleId()).isEqualTo(6L);
                    assertThat(f.evaluatedValue()).contains("max=72h");
                });
        assertThat(result.findings())
                .filteredOn(f -> !f.ruleType().equals(RuleType.REPORT_DEADLINE.name()))
                .extracting(RuleFinding::result)
                .containsOnly("PASS");
    }

    /** The id that travels is the {@code insurer_rule}'s: without it there's no FK to point at. */
    @Test
    void findingsCarryTheInsurerRuleId() {
        var result = evaluator.evaluate(
                claim(null), policy(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 6, 1)),
                history(List.of()), rules(null, null));

        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(4L);
            assertThat(f.result()).isEqualTo("FAIL");
        });
    }
}
