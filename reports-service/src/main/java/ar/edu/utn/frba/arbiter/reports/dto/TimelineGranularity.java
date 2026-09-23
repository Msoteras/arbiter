package ar.edu.utn.frba.arbiter.reports.dto;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Derived from the period length (not chosen by the caller) so a year doesn't come back as 365 points. */
public enum TimelineGranularity {

    DAY("day"),
    WEEK("week"),
    MONTH("month");

    /** Postgres' {@code date_trunc} field name. */
    private final String datePart;

    TimelineGranularity(String datePart) {
        this.datePart = datePart;
    }

    public String datePart() {
        return datePart;
    }

    /**
     * Volume matters too: a month with a dozen claims read day by day is mostly empty, so a month is
     * only daily with roughly a claim a day. Under a fortnight the daily detail is always the point.
     */
    public static TimelineGranularity forPeriod(LocalDate from, LocalDate to, long reported) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days <= 14) {
            return DAY;
        }
        if (days <= 31) {
            return reported >= days ? DAY : WEEK;
        }
        return days <= 120 ? WEEK : MONTH;
    }

    /** The bucket a date falls into — Postgres truncates weeks to Monday, so this does too. */
    public LocalDate bucketOf(LocalDate date) {
        return switch (this) {
            case DAY -> date;
            case WEEK -> date.minusDays(date.getDayOfWeek().getValue() - 1L);
            case MONTH -> date.withDayOfMonth(1);
        };
    }

    public LocalDate next(LocalDate bucket) {
        return switch (this) {
            case DAY -> bucket.plusDays(1);
            case WEEK -> bucket.plusWeeks(1);
            case MONTH -> bucket.plusMonths(1);
        };
    }
}
