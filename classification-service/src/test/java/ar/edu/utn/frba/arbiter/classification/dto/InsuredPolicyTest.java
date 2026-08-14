package ar.edu.utn.frba.arbiter.classification.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two places ask for the validity window — the hard rule D13 and the audited snapshot (D27) — so it
 * lives in one place. The edges are tested here because they're inclusive: a claim on the last day
 * of validity is covered.
 */
class InsuredPolicyTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    private InsuredPolicy policy(LocalDate from, LocalDate to) {
        return InsuredPolicy.builder().policyNumber("POL-1").effectiveFrom(from).effectiveTo(to).build();
    }

    @Test
    void aDateInsideTheWindowIsCovered() {
        assertThat(policy(FROM, TO).inForceOn(LocalDate.of(2026, 6, 13))).isTrue();
    }

    @Test
    void bothEndsAreInclusive() {
        assertThat(policy(FROM, TO).inForceOn(FROM)).isTrue();
        assertThat(policy(FROM, TO).inForceOn(TO)).isTrue();
    }

    @Test
    void aDateOutsideTheWindowIsNotCovered() {
        assertThat(policy(FROM, TO).inForceOn(FROM.minusDays(1))).isFalse();
        assertThat(policy(FROM, TO).inForceOn(TO.plusDays(1))).isFalse();
    }

    /** Validity that couldn't be verified isn't asserted — same criterion as the holder in D2. */
    @Test
    void withoutDatesNothingIsAsserted() {
        assertThat(policy(null, TO).inForceOn(FROM)).isFalse();
        assertThat(policy(FROM, null).inForceOn(FROM)).isFalse();
        assertThat(policy(FROM, TO).inForceOn(null)).isFalse();
    }
}
