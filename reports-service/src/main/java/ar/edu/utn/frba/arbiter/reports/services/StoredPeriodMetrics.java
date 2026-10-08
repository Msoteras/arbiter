package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.reports.dto.FastTrackImpact;
import ar.edu.utn.frba.arbiter.reports.dto.FraudDetection;
import ar.edu.utn.frba.arbiter.reports.dto.LegalDeadline;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsFilter;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsSummary;
import ar.edu.utn.frba.arbiter.reports.dto.RecommendationAgreement;
import ar.edu.utn.frba.arbiter.reports.dto.ReopeningRate;
import ar.edu.utn.frba.arbiter.reports.dto.ReportPeriod;
import ar.edu.utn.frba.arbiter.reports.dto.TimelineGranularity;
import ar.edu.utn.frba.arbiter.reports.dto.TimelinePoint;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository.IntakeTotals;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository.ResolutionSplit;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository.ResolvedTotals;
import ar.edu.utn.frba.arbiter.reports.models.repositories.DailyMetricsRepository;
import ar.edu.utn.frba.arbiter.reports.models.repositories.DailyMetricsRepository.Cut;
import ar.edu.utn.frba.arbiter.reports.models.repositories.DailyMetricsRepository.DailyIntake;
import ar.edu.utn.frba.arbiter.reports.models.repositories.DailyMetricsRepository.DailyResolution;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/** A closed period as its stored days added up; rates and averages are worked out here, never stored. */
@Component
@RequiredArgsConstructor
class StoredPeriodMetrics implements PeriodMetrics {

    private final DailyMetricsRepository dailyMetricsRepository;

    @Override
    public MetricsSummary summary(ReportPeriod period, MetricsFilter filter) {
        Cut cut = Cut.of(filter);
        return summary(dailyMetricsRepository.intake(period, cut),
                dailyMetricsRepository.resolution(period, cut));
    }

    @Override
    public StableMetrics stable(ReportPeriod period, MetricsFilter filter) {
        Cut cut = Cut.of(filter);
        List<DailyResolution> resolution = dailyMetricsRepository.resolution(period, cut);
        List<DailyResolution> decided = resolution.stream().filter(StoredPeriodMetrics::decided).toList();
        long decidedCases = sum(decided, DailyResolution::resolved);
        long resolvedCases = sum(resolution, DailyResolution::resolved);

        return new StableMetrics(
                summary(dailyMetricsRepository.intake(period, cut), resolution),
                RecommendationAgreement.of(
                        sum(decided, DailyResolution::agreementEligible),
                        sum(decided, DailyResolution::agreementAgreed)),
                LegalDeadline.of(decidedCases, sum(decided, DailyResolution::onTime)),
                ReopeningRate.of(resolvedCases, sum(resolution, DailyResolution::reopened)),
                dailyMetricsRepository.settled(period, cut),
                new FraudDetection(
                        decidedCases,
                        sum(decided, DailyResolution::fraudDetermined),
                        sum(decided, DailyResolution::fraudBackedByExpert),
                        // Only the rejected ones were not paid: one approved despite fraud saved nothing.
                        decided.stream()
                                .filter(row -> CaseStatus.REJECTED.name().equals(row.status()))
                                .map(DailyResolution::fraudClaimedAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)),
                fastTrackImpact(decided, decidedCases),
                dailyMetricsRepository.reportedByBranch(period, cut));
    }

    @Override
    public List<TimelinePoint> timeline(ReportPeriod period, TimelineGranularity granularity,
                                        MetricsFilter filter) {
        Cut cut = Cut.of(filter);
        Map<LocalDate, Long> reported = dailyMetricsRepository.intake(period, cut).stream()
                .collect(Collectors.groupingBy(day -> granularity.bucketOf(day.day()),
                        TreeMap::new, Collectors.summingLong(DailyIntake::reported)));
        Map<LocalDate, Long> resolved = dailyMetricsRepository.resolution(period, cut).stream()
                .collect(Collectors.groupingBy(day -> granularity.bucketOf(day.day()),
                        TreeMap::new, Collectors.summingLong(DailyResolution::resolved)));

        TreeMap<LocalDate, TimelinePoint> points = new TreeMap<>();
        reported.forEach((bucket, total) -> points.put(bucket, new TimelinePoint(bucket, total, 0)));
        resolved.forEach((bucket, total) -> points.merge(bucket, new TimelinePoint(bucket, 0, total),
                (found, added) -> new TimelinePoint(bucket, found.reported(), added.resolved())));
        return List.copyOf(points.values());
    }

    private static MetricsSummary summary(List<DailyIntake> intake, List<DailyResolution> resolution) {
        Map<String, List<DailyResolution>> byStatus =
                resolution.stream().collect(Collectors.groupingBy(DailyResolution::status));
        List<ResolvedTotals> resolved = byStatus.entrySet().stream()
                .map(status -> {
                    long count = sum(status.getValue(), DailyResolution::resolved);
                    return new ResolvedTotals(status.getKey(), count,
                            average(status.getValue(), DailyResolution::totalSeconds, count));
                })
                .toList();

        List<DailyResolution> decided = resolution.stream().filter(StoredPeriodMetrics::decided).toList();
        long decidedCases = sum(decided, DailyResolution::resolved);
        ResolutionSplit split = decidedCases == 0
                ? ResolutionSplit.NONE
                : new ResolutionSplit(
                        average(decided, DailyResolution::totalSeconds, decidedCases),
                        average(decided, DailyResolution::waitingSeconds, decidedCases));

        return MetricsSummaries.of(
                new IntakeTotals(sum(intake, DailyIntake::reported), sum(intake, DailyIntake::fastTrack)),
                resolved,
                split);
    }

    private static FastTrackImpact fastTrackImpact(List<DailyResolution> decided, long decidedCases) {
        long fastTrack = sum(decided, DailyResolution::fastTrack);
        long standard = decidedCases - fastTrack;
        double fastTrackSeconds = decided.stream().mapToDouble(DailyResolution::fastTrackSeconds).sum();
        double totalSeconds = decided.stream().mapToDouble(DailyResolution::totalSeconds).sum();
        return new FastTrackImpact(
                fastTrack,
                fastTrack == 0 ? null : fastTrackSeconds / fastTrack / 3600,
                standard,
                standard == 0 ? null : (totalSeconds - fastTrackSeconds) / standard / 3600);
    }

    /** A lapsed claim was never decided. */
    private static boolean decided(DailyResolution row) {
        return CaseStatus.APPROVED.name().equals(row.status())
                || CaseStatus.REJECTED.name().equals(row.status());
    }

    private static <T> long sum(List<T> rows, ToLongFunction<T> measure) {
        return rows.stream().mapToLong(measure).sum();
    }

    private static Double average(List<DailyResolution> rows, ToDoubleFunction<DailyResolution> seconds,
                                  long cases) {
        return cases == 0 ? null : rows.stream().mapToDouble(seconds).sum() / cases;
    }
}
