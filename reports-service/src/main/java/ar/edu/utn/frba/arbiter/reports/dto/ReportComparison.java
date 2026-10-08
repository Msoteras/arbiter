package ar.edu.utn.frba.arbiter.reports.dto;

import java.time.LocalDate;

/** The comparison a report ran with, resolved into dates. */
public record ReportComparison(ComparisonMode mode, LocalDate from, LocalDate to) {

    public ReportPeriod period() {
        return new ReportPeriod(from, to);
    }
}
