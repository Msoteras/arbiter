package ar.edu.utn.frba.arbiter.reports.dto;

import java.time.Clock;
import java.time.LocalDate;

/**
 * The shortcut periods the dashboard's selector offers, as an alternative to typing a custom
 * {@code from}/{@code to}. Each one is a window of whole calendar days ending today, included:
 * "el último mes" is the last 30 days up to and including today, not the current calendar month —
 * a dashboard opened on the 2nd of the month would otherwise show two days of data.
 */
public enum MetricsRange {

    WEEK(7),
    MONTH(30),
    QUARTER(90);

    private final int days;

    MetricsRange(int days) {
        this.days = days;
    }

    public LocalDate from(Clock clock) {
        return to(clock).minusDays(days - 1L);
    }

    public LocalDate to(Clock clock) {
        return LocalDate.now(clock);
    }
}
