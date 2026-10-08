package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.ComparisonRequest;
import ar.edu.utn.frba.arbiter.reports.dto.ExportedReport;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReport;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.FraudSummary;
import ar.edu.utn.frba.arbiter.reports.dto.ReportComparison;
import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import ar.edu.utn.frba.arbiter.reports.dto.ReportPeriod;
import ar.edu.utn.frba.arbiter.reports.exceptions.TenantNotResolvedException;
import ar.edu.utn.frba.arbiter.reports.exceptions.UnknownBranchException;
import ar.edu.utn.frba.arbiter.reports.models.repositories.FlaggedCaseRepository;
import ar.edu.utn.frba.arbiter.reports.services.export.FraudReportExporter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * The fraud report, always scoped to the caller's tenant. It flags, it doesn't determine: the
 * determination is the analyst's and comes back on each row as {@code fraudDetermined}.
 */
@Service
@RequiredArgsConstructor
public class FraudReportService {

    private final FlaggedCaseRepository flaggedCaseRepository;
    private final DailyMetricsService dailyMetricsService;
    private final ReportPeriods reportPeriods;
    private final List<FraudReportExporter> exporters;
    private final Clock clock;

    public FraudReport generate(LocalDate from, LocalDate to, Long branchId, RiskBand riskBand,
                                ComparisonRequest comparisonRequest) {
        if (!TenantContext.isResolved()) {
            throw new TenantNotResolvedException();
        }
        ReportPeriod period = reportPeriods.main(from, to);
        ReportComparison comparison = reportPeriods.comparison(period, comparisonRequest);
        String branch = branchName(branchId);

        List<FraudReportRow> rows = flaggedIn(period, branchId, riskBand);

        return new FraudReport(from, to, branch, riskBand, clock.instant(),
                summary(period, rows, branchId),
                comparison,
                summary(comparison.period(), flaggedIn(comparison.period(), branchId, riskBand), branchId),
                rows);
    }

    public ExportedReport export(LocalDate from, LocalDate to, Long branchId, RiskBand riskBand,
                                 ComparisonRequest comparisonRequest, ReportFormat format) {
        FraudReport report = generate(from, to, branchId, riskBand, comparisonRequest);
        byte[] content = exporterFor(format).export(report);
        String filename = "fraude_%s_%s.%s".formatted(from, to, format.extension());
        return new ExportedReport(filename, format, content);
    }

    /**
     * Always the cases themselves, closed period or not: signals follow the latest analysis and fraud
     * is determined months later, so neither can be stored per day.
     */
    private List<FraudReportRow> flaggedIn(ReportPeriod period, Long branchId, RiskBand riskBand) {
        ZoneId zone = clock.getZone();
        return flaggedCaseRepository.findFlaggedBetween(period.start(zone), period.end(zone), branchId, riskBand);
    }

    /** Only the denominator is stored for a closed period. */
    private FraudSummary summary(ReportPeriod period, List<FraudReportRow> rows, Long branchId) {
        ZoneId zone = clock.getZone();
        Instant start = period.start(zone);
        Instant end = period.end(zone);
        long totalClaims = dailyMetricsService.reportedClaims(period, branchId)
                .orElseGet(() -> flaggedCaseRepository.countClaimsBetween(start, end, branchId));
        return FraudSummaries.of(rows, totalClaims);
    }

    /**
     * From the catalog, not the rows: an empty result still has to name its branch. Looked up first so
     * an unknown branch fails before the query runs.
     */
    private String branchName(Long branchId) {
        if (branchId == null) {
            return null;
        }
        String name = flaggedCaseRepository.findBranchName(branchId);
        if (name == null) {
            throw new UnknownBranchException(branchId);
        }
        return name;
    }

    private FraudReportExporter exporterFor(ReportFormat format) {
        return exporters.stream()
                .filter(exporter -> exporter.format() == format)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No exporter registered for " + format));
    }
}
