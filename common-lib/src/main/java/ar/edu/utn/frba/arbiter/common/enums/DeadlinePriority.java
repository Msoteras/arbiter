package ar.edu.utn.frba.arbiter.common.enums;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Urgency of a case against its legal response deadline (art. 56 Ley 17.418). Derived, never
 * stored. The frontend mirrors it in {@code deadline-priority.ts}.
 */
public enum DeadlinePriority {

    NONE,
    WATCH,
    URGENT,
    CRITICAL,
    OVERDUE;

    /** Public so the queue's "due soon" filter uses the same threshold as the semaphore. */
    public static final long WATCH_DAYS = 10;
    private static final long URGENT_DAYS = 5;
    private static final long CRITICAL_DAYS = 2;

    /** An answered case is never flagged, no matter how close the deadline was. */
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
