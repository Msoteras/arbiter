package ar.edu.utn.frba.arbiter.common.enums;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Urgency of a case against its legal response deadline ({@code Case.response_deadline}, art. 56
 * Ley 17.418). Derived, never stored: computed from the deadline, today and whether the case was
 * already answered. Lives in common-lib because cases-service computes it and the frontend mirrors
 * it, exactly like {@link RiskBand}.
 *
 * <p>Constants in English (code convention); the Spanish label and the semaphore color are a
 * frontend concern (see {@code deadline-priority.ts}). {@link #WATCH}/{@link #URGENT}/
 * {@link #CRITICAL}/{@link #OVERDUE} map to the design system's warning/risk/danger tones.
 */
public enum DeadlinePriority {

    /** More than 10 days left, or the case is already answered: nothing to flag. */
    NONE,
    /** 10 days or fewer to the deadline. */
    WATCH,
    /** 5 days or fewer. */
    URGENT,
    /** 2 days or fewer. */
    CRITICAL,
    /** The deadline already passed and the case is still unanswered. */
    OVERDUE;

    private static final long WATCH_DAYS = 10;
    private static final long URGENT_DAYS = 5;
    private static final long CRITICAL_DAYS = 2;

    /**
     * @param deadline  the case's response deadline (never null: {@code response_deadline} is NOT NULL)
     * @param today     the reference day
     * @param responded whether the analyst already decided (terminal state) — an answered case is
     *                  never flagged, no matter how close the deadline was
     */
    public static DeadlinePriority of(LocalDate deadline, LocalDate today, boolean responded) {
        if (responded) {
            return NONE;
        }
        long daysLeft = ChronoUnit.DAYS.between(today, deadline);
        if (daysLeft < 0) {
            return OVERDUE;
        }
        if (daysLeft <= CRITICAL_DAYS) {
            return CRITICAL;
        }
        if (daysLeft <= URGENT_DAYS) {
            return URGENT;
        }
        if (daysLeft <= WATCH_DAYS) {
            return WATCH;
        }
        return NONE;
    }

    /** The two levels that trigger a notification to the analyst. */
    public boolean notifiable() {
        return this == CRITICAL || this == OVERDUE;
    }
}
