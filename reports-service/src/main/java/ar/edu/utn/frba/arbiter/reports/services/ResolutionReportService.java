package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.ExportedReport;
import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReport;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionSummary;
import ar.edu.utn.frba.arbiter.reports.dto.TimelineGranularity;
import ar.edu.utn.frba.arbiter.reports.exceptions.InvalidReportPeriodException;
import ar.edu.utn.frba.arbiter.reports.exceptions.TenantNotResolvedException;
import ar.edu.utn.frba.arbiter.reports.exceptions.UnknownBranchException;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ResolvedCaseRepository;
import ar.edu.utn.frba.arbiter.reports.services.export.ResolutionReportExporter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** The resolution report, always scoped to the caller's tenant. */
@Service
@RequiredArgsConstructor
public class ResolutionReportService {

    /** Not a business rule: a guard on how many cases one request can pull into memory. */
    static final int MAX_PERIOD_DAYS = 366;

    private final ResolvedCaseRepository resolvedCaseRepository;
    private final List<ResolutionReportExporter> exporters;
    private final Clock clock;

    public ResolutionReport generate(LocalDate from, LocalDate to, Long branchId, String claimCause) {
        if (!TenantContext.isResolved()) {
            throw new TenantNotResolvedException();
        }
        validatePeriod(from, to);
        String cause = claimCause == null || claimCause.isBlank() ? null : claimCause.strip();
        String branch = branchName(branchId);

        List<ResolutionReportRow> rows = rowsBetween(from, to, branchId, cause);
        TimelineGranularity granularity = TimelineGranularity.forPeriod(from, to, rows.size());

        return new ResolutionReport(from, to, branch, cause, clock.instant(),
                ResolutionSummaries.of(rows),
                previousSummary(from, to, branchId, cause),
                granularity,
                ResolutionSummaries.timeline(rows, from, to, clock.getZone(), granularity),
                rows);
    }

    public ExportedReport export(LocalDate from, LocalDate to, Long branchId, String claimCause,
                                 ReportFormat format) {
        ResolutionReport report = generate(from, to, branchId, claimCause);
        byte[] content = exporterFor(format).export(report);
        String filename = "resoluciones_%s_%s.%s".formatted(from, to, format.extension());
        return new ExportedReport(filename, format, content);
    }

    private ResolutionSummary previousSummary(LocalDate from, LocalDate to, Long branchId, String cause) {
        PreviousPeriod previous = PreviousPeriod.immediatelyBefore(from, to);
        return ResolutionSummaries.of(rowsBetween(previous.from(), previous.to(), branchId, cause));
    }

    /**
     * Whole calendar days in the insurer's time zone, both ends included: the upper bound is the next
     * midnight, exclusive.
     */
    private List<ResolutionReportRow> rowsBetween(LocalDate from, LocalDate to, Long branchId,
                                                  String cause) {
        ZoneId zone = clock.getZone();
        return resolvedCaseRepository.findResolvedBetween(
                from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant(),
                branchId,
                cause);
    }

    /**
     * From the catalog, not the rows: an empty result still has to name its branch. Looked up first so
     * an unknown branch fails before the query runs.
     */
    private String branchName(Long branchId) {
        if (branchId == null) {
            return null;
        }
        String name = resolvedCaseRepository.findBranchName(branchId);
        if (name == null) {
            throw new UnknownBranchException(branchId);
        }
        return name;
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
