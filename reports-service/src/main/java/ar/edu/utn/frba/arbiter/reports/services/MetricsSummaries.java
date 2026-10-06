package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsSummary;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository.IntakeTotals;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository.ResolutionSplit;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository.ResolvedTotals;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Folds a period's totals into the dashboard's {@link MetricsSummary}, wherever they were read from. */
final class MetricsSummaries {

    private MetricsSummaries() {
    }

    static MetricsSummary of(IntakeTotals intake, List<ResolvedTotals> resolved, ResolutionSplit split) {
        Map<String, ResolvedTotals> byStatus = resolved.stream()
                .collect(Collectors.toMap(ResolvedTotals::status, Function.identity()));
        long approved = countOf(byStatus, CaseStatus.APPROVED);
        long rejected = countOf(byStatus, CaseStatus.REJECTED);
        long lapsed = countOf(byStatus, CaseStatus.LAPSED);
        long resolvedCases = resolved.stream().mapToLong(ResolvedTotals::count).sum();
        // Decided, not resolved: a lapsed claim closed without anyone deciding it.
        long decided = approved + rejected;

        return new MetricsSummary(
                intake.reported(),
                intake.fastTrack(),
                resolvedCases,
                approved,
                rejected,
                lapsed,
                rate(approved, decided),
                rate(rejected, decided),
                rate(intake.fastTrack(), intake.reported()),
                averageHours(byStatus),
                split.waitingSeconds() == null ? null : split.waitingSeconds() / 3600);
    }

    private static long countOf(Map<String, ResolvedTotals> byStatus, CaseStatus status) {
        ResolvedTotals totals = byStatus.get(status.name());
        return totals == null ? 0 : totals.count();
    }

    private static Double rate(long part, long whole) {
        return whole == 0 ? null : (double) part / whole;
    }

    /** Weighted by count: averaging the per-status averages would let one rejection weigh as much as fifty approvals. */
    private static Double averageHours(Map<String, ResolvedTotals> byStatus) {
        double weightedSeconds = 0;
        long decided = 0;
        for (CaseStatus status : List.of(CaseStatus.APPROVED, CaseStatus.REJECTED)) {
            ResolvedTotals totals = byStatus.get(status.name());
            if (totals != null && totals.averageSeconds() != null) {
                weightedSeconds += totals.averageSeconds() * totals.count();
                decided += totals.count();
            }
        }
        return decided == 0 ? null : weightedSeconds / decided / 3600;
    }
}
