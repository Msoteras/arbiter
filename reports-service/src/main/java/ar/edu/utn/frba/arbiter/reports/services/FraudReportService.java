package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.ExportedReport;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReport;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import ar.edu.utn.frba.arbiter.reports.exceptions.InvalidReportPeriodException;
import ar.edu.utn.frba.arbiter.reports.exceptions.TenantNotResolvedException;
import ar.edu.utn.frba.arbiter.reports.models.repositories.FlaggedCaseRepository;
import ar.edu.utn.frba.arbiter.reports.services.export.FraudReportExporter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * The fraud report: the claims filed in a period that carry at least one fraud signal, with the
 * alert level the scoring engine gave them. Always scoped to the caller's insurer — the tenant
 * schema the JWT resolves to.
 *
 * <p>It flags, it doesn't determine. The listing exists so a person looks at those cases; the
 * determination is the analyst's, on the case, and it comes back on each row as
 * {@code fraudDetermined}.
 */
@Service
@RequiredArgsConstructor
public class FraudReportService {

    /**
     * A year, leap day included — the same cap as the resolution report. Not a business rule: a
     * guard on how many cases one request can pull into memory.
     */
    static final int MAX_PERIOD_DAYS = 366;

    /**
     * Worst alert level first, and within a level the cases where more signals coincide: the report
     * is read top-down and the first screen should be the shortlist.
     *
     * <p>The rank is the alert and not the band: a LOW band and a MEDIUM one both rank 0, the same
     * as a case the scoring never ran on. A low score is not an indicator of fraud, so it must not
     * push a case with two coinciding signals below one with a single signal and a slightly higher
     * — but still not alerting — score.
     */
    private static final Comparator<FraudReportRow> BY_URGENCY =
            Comparator.comparingInt((FraudReportRow row) -> alertRank(row.riskBand()))
                    .thenComparingInt(row -> row.signals().size())
                    .reversed()
                    .thenComparing(FraudReportRow::reportedAt, Comparator.reverseOrder())
                    .thenComparing(FraudReportRow::caseId, Comparator.reverseOrder());

    private final FlaggedCaseRepository flaggedCaseRepository;
    private final List<FraudReportExporter> exporters;
    private final Clock clock;

    public FraudReport generate(LocalDate from, LocalDate to, Long branchId, RiskBand riskBand) {
        if (!TenantContext.isResolved()) {
            throw new TenantNotResolvedException();
        }
        validatePeriod(from, to);

        // Whole calendar days in the insurer's local time, both ends included: "hasta el 30/09"
        // means up to the last second of that day, so the upper bound is the next midnight,
        // exclusive. Same convention as the resolution report.
        ZoneId zone = clock.getZone();
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();

        List<FraudReportRow> rows = flaggedCaseRepository
                .findFlaggedBetween(start, end, branchId, riskBand)
                .stream()
                .sorted(BY_URGENCY)
                .toList();
        // The denominator of the rates: every claim of the period and branch, flagged or not.
        long totalClaims = flaggedCaseRepository.countClaimsBetween(start, end, branchId);

        return new FraudReport(from, to, branchName(branchId), riskBand, clock.instant(),
                FraudSummaries.of(rows, totalClaims), rows);
    }

    public ExportedReport export(LocalDate from, LocalDate to, Long branchId, RiskBand riskBand,
                                 ReportFormat format) {
        FraudReport report = generate(from, to, branchId, riskBand);
        byte[] content = exporterFor(format).export(report);
        String filename = "fraude_%s_%s.%s".formatted(from, to, format.extension());
        return new ExportedReport(filename, format, content);
    }

    /**
     * Resolved from the catalog and not from the rows: with a filter that matched nothing there is
     * no row to take it from, and that is precisely the report that most needs to say which branch
     * it looked at.
     */
    private String branchName(Long branchId) {
        return branchId == null ? null : flaggedCaseRepository.findBranchName(branchId);
    }

    /** 2 for CRITICAL, 1 for HIGH, 0 for every band that doesn't alert and for no band at all. */
    private static int alertRank(RiskBand band) {
        if (band == RiskBand.CRITICAL) {
            return 2;
        }
        return band == RiskBand.HIGH ? 1 : 0;
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
