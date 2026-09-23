package ar.edu.utn.frba.arbiter.reports.services;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The stretch of equal length ending the day before a period starts. Equal length rather than "the
 * previous calendar month", so a custom 17-day period compares against 17 days.
 */
record PreviousPeriod(LocalDate from, LocalDate to) {

    static PreviousPeriod immediatelyBefore(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate previousTo = from.minusDays(1);
        return new PreviousPeriod(previousTo.minusDays(days - 1), previousTo);
    }
}
