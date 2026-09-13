package ar.edu.utn.frba.arbiter.classification.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rolling-year count, tested where it lives. Two very different things read it — the hard rule
 * that caps events per year, and the settlement that pays the second event of the year at a
 * reduced percentage — so the boundaries matter: one of them counting a claim the other doesn't
 * would have an analyst approving an event the rules called out of quota, or paying 100% of a
 * second event.
 */
class InsuredHistoryTest {

    private static final LocalDate EVENT = LocalDate.of(2026, 6, 13);

    @Test
    void aClaimWithNoPriorsIsTheFirstEventOfTheYear() {
        assertThat(history().eventOrdinalFor(EVENT, "Celulares")).isEqualTo(1);
    }

    @Test
    void eachPriorInsideTheWindowPushesTheOrdinalUp() {
        InsuredHistory history = history(
                claim(EVENT.minusMonths(2), "Celulares"),
                claim(EVENT.minusMonths(9), "Celulares"));

        assertThat(history.eventOrdinalFor(EVENT, "Celulares")).isEqualTo(3);
    }

    @Test
    void aPriorOlderThanTwelveMonthsNoLongerCounts() {
        InsuredHistory history = history(claim(EVENT.minusYears(1).minusDays(1), "Celulares"));

        assertThat(history.eventOrdinalFor(EVENT, "Celulares")).isEqualTo(1);
    }

    /** Exactly twelve months back is still inside: the window is closed at both ends. */
    @Test
    void aPriorExactlyTwelveMonthsBackStillCounts() {
        InsuredHistory history = history(claim(EVENT.minusYears(1), "Celulares"));

        assertThat(history.eventOrdinalFor(EVENT, "Celulares")).isEqualTo(2);
    }

    /** Quotas and reduced percentages are per branch: another branch's claim is another contract. */
    @Test
    void aPriorInAnotherBranchDoesNotCount() {
        InsuredHistory history = history(claim(EVENT.minusMonths(2), "Tecnología Portátil"));

        assertThat(history.eventOrdinalFor(EVENT, "Celulares")).isEqualTo(1);
    }

    /**
     * A claim with no branch is bad data; narrowing on it would silently undercount, so a null
     * branch on the claim under analysis doesn't filter at all.
     */
    @Test
    void aNullBranchCountsEveryPrior() {
        InsuredHistory history = history(
                claim(EVENT.minusMonths(2), "Celulares"),
                claim(EVENT.minusMonths(4), "Tecnología Portátil"));

        assertThat(history.eventOrdinalFor(EVENT, null)).isEqualTo(3);
    }

    @Test
    void aPriorAfterTheEventDoesNotCount() {
        InsuredHistory history = history(claim(EVENT.plusDays(1), "Celulares"));

        assertThat(history.eventOrdinalFor(EVENT, "Celulares")).isEqualTo(1);
    }

    /** No event date, no window: defaults to the first event rather than guessing. */
    @Test
    void defaultsToTheFirstEventWithNoDateOrNoHistory() {
        assertThat(history(claim(EVENT.minusMonths(2), "Celulares")).eventOrdinalFor(null, "Celulares"))
                .isEqualTo(1);
        assertThat(InsuredHistory.builder().insuredId("40.123.456").build()
                .eventOrdinalFor(EVENT, "Celulares"))
                .isEqualTo(1);
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

    private InsuredHistory.ClaimRecord claim(LocalDate date, String branch) {
        return InsuredHistory.ClaimRecord.builder()
                .claimId("c-" + date)
                .date(date)
                .branch(branch)
                .status("LIQUIDADO")
                .build();
    }
}
