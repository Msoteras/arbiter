package ar.edu.utn.frba.arbiter.classification.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Boundaries matter: both the events-per-year rule and the settlement rely on this count. */
class InsuredHistoryTest {

    private static final LocalDate EVENT = LocalDate.of(2026, 6, 13);
    private static final String POLICY = "POL-CEL-2024-001";
    private static final String OTHER_POLICY = "POL-CEL-2025-077";
    /** Anniversary on March 1st: the current policy year runs from 2026-03-01. */
    private static final InsuredPolicy CURRENT_POLICY = policy(POLICY, LocalDate.of(2024, 3, 1));

    @Test
    void aClaimWithNoPriorsIsTheFirstEventOfTheYear() {
        assertThat(history().eventOrdinalFor(EVENT, CURRENT_POLICY)).isEqualTo(1);
    }

    /** Martina's case: the iPhone's theft doesn't use up the Galaxy's cap. */
    @Test
    void twoPoliciesOfTheSameBranchAreNotAddedUp() {
        InsuredHistory history = history(settled(EVENT.minusMonths(2), OTHER_POLICY, "Robo"));

        assertThat(history.eventOrdinalFor(EVENT, CURRENT_POLICY)).isEqualTo(1);
    }

    @Test
    void theftAndDamageOnTheSamePolicyAreAddedUp() {
        InsuredHistory history = history(
                settled(EVENT.minusMonths(2), POLICY, "Robo"),
                settled(EVENT.minusMonths(1), POLICY, "Daño accidental"));

        assertThat(history.eventOrdinalFor(EVENT, CURRENT_POLICY)).isEqualTo(3);
    }

    /** Inside the last 12 months, but before the anniversary. */
    @Test
    void aClaimFromThePreviousPolicyYearDoesNotCount() {
        InsuredHistory history = history(settled(LocalDate.of(2026, 2, 28), POLICY, "Robo"));

        assertThat(history.eventOrdinalFor(EVENT, CURRENT_POLICY)).isEqualTo(1);
    }

    @Test
    void aClaimOnTheAnniversaryCountsInTheNewPolicyYear() {
        InsuredHistory history = history(settled(LocalDate.of(2026, 3, 1), POLICY, "Robo"));

        assertThat(history.eventOrdinalFor(EVENT, CURRENT_POLICY)).isEqualTo(2);
    }

    /** The contracts cap indemnities: a rejected claim paid nothing. */
    @Test
    void aRejectedClaimDoesNotCount() {
        InsuredHistory history = history(claim(EVENT.minusMonths(2), POLICY, "Robo", "RECHAZADO"));

        assertThat(history.eventOrdinalFor(EVENT, CURRENT_POLICY)).isEqualTo(1);
    }

    /** In favor of the insured: a claim that can't be tied to this policy isn't charged to it. */
    @Test
    void aClaimWithNoPolicyNumberDoesNotCount() {
        InsuredHistory history = history(settled(EVENT.minusMonths(2), null, "Robo"));

        assertThat(history.eventOrdinalFor(EVENT, CURRENT_POLICY)).isEqualTo(1);
    }

    @Test
    void aPriorAfterTheEventDoesNotCount() {
        InsuredHistory history = history(settled(EVENT.plusDays(1), POLICY, "Robo"));

        assertThat(history.eventOrdinalFor(EVENT, CURRENT_POLICY)).isEqualTo(1);
    }

    /** Without a start date there is no anniversary: the last 12 months, closed at both ends. */
    @Test
    void withoutAPolicyStartTheWindowIsTheLastTwelveMonths() {
        InsuredPolicy noStart = policy(POLICY, null);

        assertThat(history(settled(EVENT.minusYears(1), POLICY, "Robo")).eventOrdinalFor(EVENT, noStart))
                .isEqualTo(2);
        assertThat(history(settled(EVENT.minusYears(1).minusDays(1), POLICY, "Robo")).eventOrdinalFor(EVENT, noStart))
                .isEqualTo(1);
    }

    @Test
    void defaultsToTheFirstEventWithNoDateNoPolicyOrNoHistory() {
        InsuredHistory history = history(settled(EVENT.minusMonths(2), POLICY, "Robo"));

        assertThat(history.eventOrdinalFor(null, CURRENT_POLICY)).isEqualTo(1);
        assertThat(history.eventOrdinalFor(EVENT, null)).isEqualTo(1);
        assertThat(InsuredHistory.builder().insuredId("40.123.456").build()
                .eventOrdinalFor(EVENT, CURRENT_POLICY))
                .isEqualTo(1);
    }

    @Test
    void thePolicyYearStartsOnTheLastAnniversary() {
        LocalDate start = LocalDate.of(2024, 3, 1);

        assertThat(InsuredHistory.policyYearStart(LocalDate.of(2026, 2, 28), start)).isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat(InsuredHistory.policyYearStart(LocalDate.of(2026, 3, 1), start)).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(InsuredHistory.policyYearStart(LocalDate.of(2024, 3, 10), start)).isEqualTo(start);
    }

    /** In a common year, a policy started on Feb 29 renews on Feb 28. */
    @Test
    void aPolicyStartedOnFebruary29RenewsOnFebruary28() {
        LocalDate start = LocalDate.of(2024, 2, 29);

        assertThat(InsuredHistory.policyYearStart(LocalDate.of(2025, 2, 28), start)).isEqualTo(LocalDate.of(2025, 2, 28));
        assertThat(InsuredHistory.policyYearStart(LocalDate.of(2025, 2, 27), start)).isEqualTo(start);
    }

    private static InsuredPolicy policy(String number, LocalDate start) {
        return InsuredPolicy.builder()
                .policyNumber(number)
                .effectiveFrom(start == null ? null : start.atStartOfDay())
                .build();
    }

    private InsuredHistory history(InsuredHistory.ClaimRecord... claims) {
        return InsuredHistory.builder()
                .insuredId("40.123.456")
                .previousClaimsCount(claims.length)
                .totalAmountClaimed(BigDecimal.ZERO)
                .customerSince(LocalDate.of(2024, 3, 1))
                .claims(List.of(claims))
                .build();
    }

    private InsuredHistory.ClaimRecord settled(LocalDate date, String policyNumber, String coverage) {
        return claim(date, policyNumber, coverage, "LIQUIDADO");
    }

    private InsuredHistory.ClaimRecord claim(LocalDate date, String policyNumber, String coverage, String status) {
        return InsuredHistory.ClaimRecord.builder()
                .claimId("c-" + date)
                .date(date)
                .policyNumber(policyNumber)
                .branch("Celulares")
                .coverageName(coverage)
                .status(status)
                .build();
    }
}
