package ar.edu.utn.frba.arbiter.reports.dto;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Shortcut periods: windows of whole days ending today, included. MONTH is the last 30 days, not the
 * calendar month, so a dashboard opened on the 2nd does not show two days of data.
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
