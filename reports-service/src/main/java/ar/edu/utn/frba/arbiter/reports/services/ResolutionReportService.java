package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.ComparisonRequest;
import ar.edu.utn.frba.arbiter.reports.dto.ExportedReport;
import ar.edu.utn.frba.arbiter.reports.dto.ReportComparison;
import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import ar.edu.utn.frba.arbiter.reports.dto.ReportPeriod;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReport;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionSummary;
import ar.edu.utn.frba.arbiter.reports.dto.TimelineGranularity;
import ar.edu.utn.frba.arbiter.reports.exceptions.TenantNotResolvedException;
import ar.edu.utn.frba.arbiter.reports.exceptions.UnknownBranchException;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ResolvedCaseRepository;
import ar.edu.utn.frba.arbiter.reports.services.export.ResolutionReportExporter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** The resolution report, always scoped to the caller's tenant. */
@Service
@RequiredArgsConstructor
public class ResolutionReportService {

    private final ResolvedCaseRepository resolvedCaseRepository;
    private final DailyMetricsService dailyMetricsService;
    private final ReportPeriods reportPeriods;
    private final List<ResolutionReportExporter> exporters;
    private final Clock clock;

    public ResolutionReport generate(LocalDate from, LocalDate to, Long branchId, String claimCause,
                                     ComparisonRequest comparisonRequest) {
        if (!TenantContext.isResolved()) {
            throw new TenantNotResolvedException();
        }
        ReportPeriod period = reportPeriods.main(from, to);
        ReportComparison comparison = reportPeriods.comparison(period, comparisonRequest);
        String cause = claimCause == null || claimCause.isBlank() ? null : claimCause.strip();
        String branch = branchName(branchId);

        List<ResolutionReportRow> rows = rowsIn(period, branchId, cause);
        TimelineGranularity granularity = TimelineGranularity.forPeriod(from, to, rows.size());

        return new ResolutionReport(from, to, branch, cause, clock.instant(),
                ResolutionSummaries.of(rows),
                comparison,
                comparisonSummary(comparison.period(), branchId, cause),
                granularity,
                ResolutionSummaries.timeline(rows, from, to, clock.getZone(), granularity),
                rows);
    }

    public ExportedReport export(LocalDate from, LocalDate to, Long branchId, String claimCause,
                                 ComparisonRequest comparisonRequest, ReportFormat format) {
        ResolutionReport report = generate(from, to, branchId, claimCause, comparisonRequest);
        byte[] content = exporterFor(format).export(report);
        String filename = "resoluciones_%s_%s.%s".formatted(from, to, format.extension());
        return new ExportedReport(filename, format, content);
    }

    /** Only its totals are shown, so a closed comparison period never loads its cases. */
    private ResolutionSummary comparisonSummary(ReportPeriod period, Long branchId, String cause) {
        return dailyMetricsService.resolutionSummary(period, branchId, cause)
                .orElseGet(() -> ResolutionSummaries.of(rowsIn(period, branchId, cause)));
    }

    private List<ResolutionReportRow> rowsIn(ReportPeriod period, Long branchId, String cause) {
        ZoneId zone = clock.getZone();
        return resolvedCaseRepository.findResolvedBetween(period.start(zone), period.end(zone), branchId, cause);
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

    private ResolutionReportExporter exporterFor(ReportFormat format) {
        return exporters.stream()
                .filter(exporter -> exporter.format() == format)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No exporter registered for " + format));
    }
}
