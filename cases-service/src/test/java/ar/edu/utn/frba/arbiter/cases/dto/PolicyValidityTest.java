package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse.Validity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validity is computed by the backend and sent precomputed, so consumers with different clocks
 * (backend in UTC, browser in Argentine time) can't disagree on the day a policy expires.
 */
class PolicyValidityTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 10, 12, 0);

    @Test
    void aPolicyInsideItsCoveragePeriod_isCurrent() {
        assertThat(Validity.at(NOW.minusMonths(3), NOW.plusMonths(9), NOW))
                .isEqualTo(Validity.CURRENT);
    }

    @Test
    void aPolicyPastItsEndDate_isExpired() {
        assertThat(Validity.at(NOW.minusYears(2), NOW.minusDays(1), NOW))
                .isEqualTo(Validity.EXPIRED);
    }

    /** Insurers sell policies with a future start date; those must not read as current. */
    @Test
    void aPolicyThatHasNotStartedYet_isNotYetActive() {
        assertThat(Validity.at(NOW.plusDays(20), NOW.plusYears(1), NOW))
                .isEqualTo(Validity.NOT_YET_ACTIVE);
    }

    @Test
    void theEndOfCoverageIsComparedWithItsTime_notJustTheDay() {
        LocalDateTime endsThisMorning = NOW.withHour(8);
        assertThat(Validity.at(NOW.minusYears(1), endsThisMorning, NOW))
                .isEqualTo(Validity.EXPIRED);

        LocalDateTime endsTonight = NOW.withHour(23).withMinute(59);
        assertThat(Validity.at(NOW.minusYears(1), endsTonight, NOW))
                .isEqualTo(Validity.CURRENT);
    }

    /** Incomplete dates must not read as expired and hide the policy. */
    @Test
    void missingDates_readAsCurrent() {
        assertThat(Validity.at(null, null, NOW)).isEqualTo(Validity.CURRENT);
    }
}
