package ar.edu.utn.frba.arbiter.reports.dto;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * How wide each point of the timeline chart is. Not chosen by the caller: it's derived from the
 * length of the period, so a week reads day by day and a year doesn't come back as 365 points
 * nobody can tell apart on a phone screen.
 */
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
     * The period alone doesn't settle it: volume does too. An insurer with a dozen claims a month
     * read day by day is a row of thirty points, almost all of them zero, with three spikes — a
     * chart that is mostly empty space and says less than the same data grouped by week. So a
     * month's worth of days is only read day by day when there is roughly a claim a day to show.
     *
     * <p>A short window is the exception: under a fortnight the daily detail IS the point, whatever
     * the volume, and grouping it by week would leave one or two bars.
     *
     * @param reported how many claims were filed in the period
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
