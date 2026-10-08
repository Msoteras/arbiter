package ar.edu.utn.frba.arbiter.reports.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Whole calendar days in the insurer's time zone, both ends included. */
public record ReportPeriod(LocalDate from, LocalDate to) {

    public long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    public Instant start(ZoneId zone) {
        return from.atStartOfDay(zone).toInstant();
    }

    /** The next midnight, exclusive. */
    public Instant end(ZoneId zone) {
        return to.plusDays(1).atStartOfDay(zone).toInstant();
    }
}
