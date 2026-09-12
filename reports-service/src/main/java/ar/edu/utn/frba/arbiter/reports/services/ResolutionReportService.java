package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.ExportedReport;
import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReport;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow;
import ar.edu.utn.frba.arbiter.reports.exceptions.InvalidReportPeriodException;
import ar.edu.utn.frba.arbiter.reports.exceptions.TenantNotResolvedException;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ResolvedCaseRepository;
import ar.edu.utn.frba.arbiter.reports.services.export.ResolutionReportExporter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The resolution report: the claims an insurer resolved in a period, with how long each took, how
 * it was classified and what the analyst decided. Always scoped to the caller's insurer — the
 * tenant schema the JWT resolves to.
 */
@Service
@RequiredArgsConstructor
public class ResolutionReportService {

    /**
     * A year, leap day included. Not a business rule — a guard on how many cases one request can
     * pull into memory to render a file.
     */
    static final int MAX_PERIOD_DAYS = 366;

    private final ResolvedCaseRepository resolvedCaseRepository;
    private final List<ResolutionReportExporter> exporters;
    private final Clock clock;

    public ResolutionReport generate(LocalDate from, LocalDate to, String claimCause) {
        if (!TenantContext.isResolved()) {
            throw new TenantNotResolvedException();
        }
        validatePeriod(from, to);
        String cause = claimCause == null || claimCause.isBlank() ? null : claimCause.strip();

        // Whole calendar days in the insurer's local time, both ends included: "hasta el 31/08"
        // means up to the last second of that day, so the upper bound is the next midnight,
        // exclusive.
        ZoneId zone = clock.getZone();
        List<ResolutionReportRow> rows = resolvedCaseRepository.findResolvedBetween(
                from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant(),
                cause);
        return new ResolutionReport(from, to, cause, clock.instant(), rows);
    }

    public ExportedReport export(LocalDate from, LocalDate to, String claimCause, ReportFormat format) {
        ResolutionReport report = generate(from, to, claimCause);
        byte[] content = exporterFor(format).export(report);
        String filename = "resoluciones_%s_%s.%s".formatted(from, to, format.extension());
        return new ExportedReport(filename, format, content);
    }

    private static void validatePeriod(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new InvalidReportPeriodException("'from' (%s) is after 'to' (%s)".formatted(from, to));
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_PERIOD_DAYS) {
            throw new InvalidReportPeriodException(
                    "The period can't be longer than %d days".formatted(MAX_PERIOD_DAYS));
        }
    }

    private ResolutionReportExporter exporterFor(ReportFormat format) {
        return exporters.stream()
                .filter(exporter -> exporter.format() == format)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No exporter registered for " + format));
    }
}
