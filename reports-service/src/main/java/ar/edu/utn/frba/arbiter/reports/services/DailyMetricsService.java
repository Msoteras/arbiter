package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsRecalculation;
import ar.edu.utn.frba.arbiter.reports.dto.ReportPeriod;
import ar.edu.utn.frba.arbiter.reports.exceptions.InvalidReportPeriodException;
import ar.edu.utn.frba.arbiter.reports.exceptions.TenantNotResolvedException;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionSummary;
import ar.edu.utn.frba.arbiter.reports.models.repositories.DailyMetricsRepository;
import ar.edu.utn.frba.arbiter.reports.models.repositories.DailyMetricsRepository.Cut;
import ar.edu.utn.frba.arbiter.reports.models.repositories.DailyMetricsRepository.DailyIntake;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Stored in the tenant's schema rather than cached in memory: the backend scales by adding instances,
 * and two of them must not show different numbers for the same closed month.
 */
@Service
@RequiredArgsConstructor
public class DailyMetricsService {

    private final DailyMetricsRepository dailyMetricsRepository;
    private final ReportPeriods reportPeriods;
    private final Clock clock;

    /** Stores the missing days first. False if the period includes today or has a day in flight. */
    @Transactional
    public boolean covers(ReportPeriod period) {
        return reportPeriods.isClosed(period) && dailyMetricsRepository.store(period, clock.getZone());
    }

    /** @return empty when the period isn't covered */
    @Transactional
    public OptionalLong reportedClaims(ReportPeriod period, Long branchId) {
        if (!covers(period)) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(dailyMetricsRepository.intake(period, new Cut(branchId, null, null)).stream()
                .mapToLong(DailyIntake::reported)
                .sum());
    }

    /** @return empty when the period isn't covered */
    @Transactional
    public Optional<ResolutionSummary> resolutionSummary(ReportPeriod period, Long branchId,
                                                         String claimCause) {
        if (!covers(period)) {
            return Optional.empty();
        }
        Cut cut = new Cut(branchId, null, claimCause);
        return Optional.of(ResolutionSummaries.ofStored(
                dailyMetricsRepository.resolution(period, cut),
                dailyMetricsRepository.resolvedByClaimCause(period, cut)));
    }

    /** The only way a stored day changes. */
    @Transactional
    public MetricsRecalculation recalculate(LocalDate from, LocalDate to) {
        if (!TenantContext.isResolved()) {
            throw new TenantNotResolvedException();
        }
        ReportPeriod period = reportPeriods.main(from, to);
        if (!reportPeriods.isClosed(period)) {
            throw new InvalidReportPeriodException("Only closed days are stored: 'to' must be before today");
        }
        dailyMetricsRepository.forget(period);
        dailyMetricsRepository.store(period, clock.getZone());
        return new MetricsRecalculation(from, to, dailyMetricsRepository.storedDays(period));
    }
}
