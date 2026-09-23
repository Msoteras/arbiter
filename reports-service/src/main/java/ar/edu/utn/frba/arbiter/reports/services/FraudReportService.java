package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.ExportedReport;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReport;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.FraudSummary;
import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import ar.edu.utn.frba.arbiter.reports.exceptions.InvalidReportPeriodException;
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
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The fraud report, always scoped to the caller's tenant. It flags, it doesn't determine: the
 * determination is the analyst's and comes back on each row as {@code fraudDetermined}.
 */
@Service
@RequiredArgsConstructor
public class FraudReportService {

    /** Not a business rule: a guard on how many cases one request can pull into memory. */
    static final int MAX_PERIOD_DAYS = 366;

    private final FlaggedCaseRepository flaggedCaseRepository;
    private final List<FraudReportExporter> exporters;
    private final Clock clock;

    public FraudReport generate(LocalDate from, LocalDate to, Long branchId, RiskBand riskBand) {
        if (!TenantContext.isResolved()) {
            throw new TenantNotResolvedException();
        }
        validatePeriod(from, to);
        String branch = branchName(branchId);

        // Whole calendar days in the insurer's time zone, both ends included: the upper bound is the
        // next midnight, exclusive.
        ZoneId zone = clock.getZone();
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();

        List<FraudReportRow> rows = flaggedCaseRepository.findFlaggedBetween(start, end, branchId, riskBand);
        long totalClaims = flaggedCaseRepository.countClaimsBetween(start, end, branchId);

        return new FraudReport(from, to, branch, riskBand, clock.instant(),
                FraudSummaries.of(rows, totalClaims),
                previousSummary(from, to, zone, branchId, riskBand), rows);
    }

    private FraudSummary previousSummary(LocalDate from, LocalDate to, ZoneId zone, Long branchId,
                                         RiskBand riskBand) {
        PreviousPeriod previous = PreviousPeriod.immediatelyBefore(from, to);
        Instant start = previous.from().atStartOfDay(zone).toInstant();
        Instant end = previous.to().plusDays(1).atStartOfDay(zone).toInstant();
        List<FraudReportRow> rows = flaggedCaseRepository.findFlaggedBetween(start, end, branchId, riskBand);
        long totalClaims = flaggedCaseRepository.countClaimsBetween(start, end, branchId);
        return FraudSummaries.of(rows, totalClaims);
    }

    public ExportedReport export(LocalDate from, LocalDate to, Long branchId, RiskBand riskBand,
                                 ReportFormat format) {
        FraudReport report = generate(from, to, branchId, riskBand);
        byte[] content = exporterFor(format).export(report);
        String filename = "fraude_%s_%s.%s".formatted(from, to, format.extension());
        return new ExportedReport(filename, format, content);
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

    private static void validatePeriod(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new InvalidReportPeriodException("'from' (%s) is after 'to' (%s)".formatted(from, to));
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_PERIOD_DAYS) {
            throw new InvalidReportPeriodException(
                    "The period can't be longer than %d days".formatted(MAX_PERIOD_DAYS));
        }
    }
}
