package ar.edu.utn.frba.arbiter.reports.services;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The stretch of equal length ending the day before a period starts — what "the previous period"
 * means everywhere this module compares a figure against it (the metrics dashboard and both
 * reports). It's what turns a lone figure into a direction: "30,8 días" becomes "30,8 días, 4 más
 * que antes".
 *
 * <p>Equal length and immediately before, rather than "the previous calendar month": a custom
 * 17-day period has to compare against 17 days or the delta ends up measuring the calendar, not
 * the claims.
 */
record PreviousPeriod(LocalDate from, LocalDate to) {

    static PreviousPeriod immediatelyBefore(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate previousTo = from.minusDays(1);
        return new PreviousPeriod(previousTo.minusDays(days - 1), previousTo);
    }
}
