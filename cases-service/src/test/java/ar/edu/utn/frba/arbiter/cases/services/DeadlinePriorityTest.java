package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.common.enums.DeadlinePriority;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lives in cases-service, not common-lib: common-lib has no JUnit 5 test runner wired, and
 * cases-service is the module that actually depends on and computes {@link DeadlinePriority}.
 */
class DeadlinePriorityTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);

    /** Priority of a deadline that many days from today, for an unanswered case. */
    private static DeadlinePriority daysOut(long days) {
        return DeadlinePriority.of(TODAY.plusDays(days), TODAY, false);
    }

    @Test
    void moreThanTenDays_isNone() {
        assertThat(daysOut(11)).isEqualTo(DeadlinePriority.NONE);
        assertThat(daysOut(30)).isEqualTo(DeadlinePriority.NONE);
    }

    @Test
    void tenDownToSix_isWatch() {
        assertThat(daysOut(10)).isEqualTo(DeadlinePriority.WATCH);
        assertThat(daysOut(6)).isEqualTo(DeadlinePriority.WATCH);
    }

    @Test
    void fiveDownToThree_isUrgent() {
        assertThat(daysOut(5)).isEqualTo(DeadlinePriority.URGENT);
        assertThat(daysOut(3)).isEqualTo(DeadlinePriority.URGENT);
    }

    @Test
    void twoDownToToday_isCritical() {
        assertThat(daysOut(2)).isEqualTo(DeadlinePriority.CRITICAL);
        assertThat(daysOut(1)).isEqualTo(DeadlinePriority.CRITICAL);
        assertThat(daysOut(0)).isEqualTo(DeadlinePriority.CRITICAL);
    }

    @Test
    void past_isOverdue() {
        assertThat(daysOut(-1)).isEqualTo(DeadlinePriority.OVERDUE);
        assertThat(daysOut(-40)).isEqualTo(DeadlinePriority.OVERDUE);
    }

    @Test
    void answeredCase_isNone_evenWhenOverdue() {
        assertThat(DeadlinePriority.of(TODAY.minusDays(5), TODAY, true))
                .isEqualTo(DeadlinePriority.NONE);
        assertThat(DeadlinePriority.of(TODAY, TODAY, true)).isEqualTo(DeadlinePriority.NONE);
    }

    @Test
    void onlyCriticalAndOverdueAreNotifiable() {
        assertThat(DeadlinePriority.CRITICAL.notifiable()).isTrue();
        assertThat(DeadlinePriority.OVERDUE.notifiable()).isTrue();
        assertThat(DeadlinePriority.URGENT.notifiable()).isFalse();
        assertThat(DeadlinePriority.WATCH.notifiable()).isFalse();
        assertThat(DeadlinePriority.NONE.notifiable()).isFalse();
    }
}
