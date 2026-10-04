package ar.edu.utn.frba.arbiter.classification.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** The validity window's edges are inclusive: a claim on the last day is covered. */
class InsuredPolicyTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 12, 31, 23, 59);

    private InsuredPolicy policy(LocalDateTime from, LocalDateTime to) {
        return InsuredPolicy.builder().policyNumber("POL-1").effectiveFrom(from).effectiveTo(to).build();
    }

    @Test
    void aDateInsideTheWindowIsCovered() {
        assertThat(policy(FROM, TO).inForceOn(LocalDateTime.of(2026, 6, 13, 12, 0))).isTrue();
    }

    @Test
    void bothEndsAreInclusive() {
        assertThat(policy(FROM, TO).inForceOn(FROM)).isTrue();
        assertThat(policy(FROM, TO).inForceOn(TO)).isTrue();
    }

    @Test
    void aDateOutsideTheWindowIsNotCovered() {
        assertThat(policy(FROM, TO).inForceOn(FROM.minusMinutes(1))).isFalse();
        assertThat(policy(FROM, TO).inForceOn(TO.plusMinutes(1))).isFalse();
    }

    /** Validity that can't be verified isn't asserted. */
    @Test
    void withoutDatesNothingIsAsserted() {
        assertThat(policy(null, TO).inForceOn(FROM)).isFalse();
        assertThat(policy(FROM, null).inForceOn(FROM)).isFalse();
        assertThat(policy(FROM, TO).inForceOn(null)).isFalse();
    }

    /** Validity starts at an exact hour: an event earlier that same day is not covered. */
    @Test
    void sameCalendarDayButBeforeTheStartHourIsNotCovered() {
        LocalDateTime start = LocalDateTime.of(2026, 6, 14, 12, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 14, 12, 0);
        LocalDateTime event = LocalDateTime.of(2026, 6, 14, 9, 40); // 2h20m earlier, same day

        assertThat(policy(start, end).inForceOn(event)).isFalse();
    }
}
