package ar.edu.utn.frba.arbiter.classification.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Boundaries matter: both the events-per-year rule and the settlement rely on this count. */
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

    /** Per branch: another branch's claim is another contract. */
    @Test
    void aPriorInAnotherBranchDoesNotCount() {
        InsuredHistory history = history(claim(EVENT.minusMonths(2), "Tecnología Portátil"));

        assertThat(history.eventOrdinalFor(EVENT, "Celulares")).isEqualTo(1);
    }

    /** A null branch doesn't filter: narrowing on bad data would silently undercount. */
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

    /** No event date: defaults to the first event rather than guessing. */
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
