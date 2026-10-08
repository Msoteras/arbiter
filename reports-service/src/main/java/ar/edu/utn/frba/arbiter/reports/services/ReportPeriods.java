package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.dto.ComparisonMode;
import ar.edu.utn.frba.arbiter.reports.dto.ComparisonRequest;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsRange;
import ar.edu.utn.frba.arbiter.reports.dto.ReportComparison;
import ar.edu.utn.frba.arbiter.reports.dto.ReportPeriod;
import ar.edu.utn.frba.arbiter.reports.exceptions.InvalidReportPeriodException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/** Resolves and validates both of a report's periods, so the three reports can't disagree on either. */
@Component
@RequiredArgsConstructor
public class ReportPeriods {

    /** Not a business rule: a guard on how much one request can scan. */
    static final int MAX_PERIOD_DAYS = 366;

    private static final MetricsRange DEFAULT_RANGE = MetricsRange.MONTH;

    private final Clock clock;

    /**
     * @param range mutually exclusive with {@code from}/{@code to}; with all three absent,
     *              {@link #DEFAULT_RANGE}
     */
    public ReportPeriod main(MetricsRange range, LocalDate from, LocalDate to) {
        boolean custom = from != null || to != null;
        if (range != null && custom) {
            throw new InvalidReportPeriodException("Use either 'range' or 'from'/'to', not both");
        }
        if (!custom) {
            MetricsRange effective = range == null ? DEFAULT_RANGE : range;
            return new ReportPeriod(effective.from(clock), effective.to(clock));
        }
        return main(from, to);
    }

    public ReportPeriod main(LocalDate from, LocalDate to) {
        return validated(from, to, "period");
    }

    public ReportComparison comparison(ReportPeriod main, ComparisonRequest request) {
        ComparisonMode mode = request.mode() == null ? ComparisonMode.PREVIOUS_PERIOD : request.mode();
        if (mode != ComparisonMode.CUSTOM && (request.from() != null || request.to() != null)) {
            throw new InvalidReportPeriodException(
                    "'compareFrom'/'compareTo' only apply to a CUSTOM comparison");
        }
        ReportPeriod period = switch (mode) {
            // Equal length rather than "the previous calendar month", so a 17-day period compares
            // against 17 days.
            case PREVIOUS_PERIOD -> {
                LocalDate to = main.from().minusDays(1);
                yield new ReportPeriod(to.minusDays(main.days() - 1), to);
            }
            // Same dates rather than same length: February 29 falls back to the 28th.
            case SAME_PERIOD_LAST_YEAR ->
                    new ReportPeriod(main.from().minusYears(1), main.to().minusYears(1));
            case CUSTOM -> validated(request.from(), request.to(), "comparison period");
        };
        return new ReportComparison(mode, period.from(), period.to());
    }

    /** A period that includes today is still moving. */
    public boolean isClosed(ReportPeriod period) {
        return period.to().isBefore(LocalDate.now(clock));
    }

    private static ReportPeriod validated(LocalDate from, LocalDate to, String name) {
        if (from == null || to == null) {
            throw new InvalidReportPeriodException("A custom %s needs both ends".formatted(name));
        }
        if (from.isAfter(to)) {
            throw new InvalidReportPeriodException(
                    "The start of the %s (%s) is after its end (%s)".formatted(name, from, to));
        }
        ReportPeriod period = new ReportPeriod(from, to);
        if (period.days() > MAX_PERIOD_DAYS) {
            throw new InvalidReportPeriodException(
                    "The %s can't be longer than %d days".formatted(name, MAX_PERIOD_DAYS));
        }
        return period;
    }
}
