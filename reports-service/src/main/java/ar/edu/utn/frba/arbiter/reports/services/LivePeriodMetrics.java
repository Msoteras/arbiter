package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.dto.MetricsFilter;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsSummary;
import ar.edu.utn.frba.arbiter.reports.dto.ReportPeriod;
import ar.edu.utn.frba.arbiter.reports.dto.TimelineGranularity;
import ar.edu.utn.frba.arbiter.reports.dto.TimelinePoint;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** Read from the cases themselves: the only source for a period that includes today. */
@Component
@RequiredArgsConstructor
class LivePeriodMetrics implements PeriodMetrics {

    private final ClaimMetricsRepository claimMetricsRepository;
    private final Clock clock;

    @Override
    public MetricsSummary summary(ReportPeriod period, MetricsFilter filter) {
        Instant start = period.start(clock.getZone());
        Instant end = period.end(clock.getZone());
        return MetricsSummaries.of(
                claimMetricsRepository.intakeTotals(start, end, filter),
                claimMetricsRepository.resolvedTotals(start, end, filter),
                claimMetricsRepository.resolutionSplit(start, end, filter));
    }

    @Override
    public StableMetrics stable(ReportPeriod period, MetricsFilter filter) {
        ZoneId zone = clock.getZone();
        Instant start = period.start(zone);
        Instant end = period.end(zone);
        return new StableMetrics(
                summary(period, filter),
                claimMetricsRepository.recommendationAgreement(start, end, filter),
                claimMetricsRepository.legalDeadlineCompliance(start, end, zone, filter),
                claimMetricsRepository.reopeningRate(start, end, filter),
                claimMetricsRepository.settledAmounts(start, end, filter),
                claimMetricsRepository.fraudDetection(start, end, filter),
                claimMetricsRepository.fastTrackImpact(start, end, filter),
                claimMetricsRepository.countByBranch(start, end, filter));
    }

    @Override
    public List<TimelinePoint> timeline(ReportPeriod period, TimelineGranularity granularity,
                                        MetricsFilter filter) {
        ZoneId zone = clock.getZone();
        return claimMetricsRepository.timeline(period.start(zone), period.end(zone), granularity, zone, filter);
    }
}
