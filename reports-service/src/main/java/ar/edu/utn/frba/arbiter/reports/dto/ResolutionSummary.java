package ar.edu.utn.frba.arbiter.reports.dto;

import java.util.List;

/**
 * The aggregate head of the resolution report: the four figures H0019 asks the report to show, over
 * the very same cases the detail lists.
 *
 * <p>It is computed from the report's rows rather than with its own queries, so the head and the
 * table can never disagree — a total that contradicts the list underneath it is worse than no total.
 *
 * <p><b>Not the dashboard's numbers.</b> {@link ClaimMetrics} measures the claims <b>filed</b> in the
 * period; this one measures the claims <b>closed</b> in it, which is what a resolution report is. The
 * same week yields different figures in each, on purpose: a claim filed in March and closed in April
 * is counted by the dashboard in March and by this report in April. {@code fastTrackRate} is the
 * clearest case — here it answers "of everything we closed, how much went through Fast Track",
 * whereas {@link MetricsSummary#fastTrackRate()} answers "of everything that came in, how much
 * qualified".
 *
 * @param totalCases     resolved cases in the period, matching the number of rows
 * @param averageMinutes from the denuncia to the final status, averaged over those cases; null when
 *                       there are none, because an average of nothing is unknown, not zero
 * @param fastTrackCases how many of them the deterministic gate resolved
 * @param fastTrackRate  {@code fastTrackCases / totalCases}, as a fraction between 0 and 1 (the
 *                       frontend's percent pipe formats it); null when there is nothing to divide
 * @param byStatus       the resolved cases by the final status they closed in, busiest first
 * @param byClaimCause   the resolved cases by claim cause ("hecho generador"), busiest first
 */
public record ResolutionSummary(
        long totalCases,
        Double averageMinutes,
        long fastTrackCases,
        Double fastTrackRate,
        List<MetricCount> byStatus,
        List<MetricCount> byClaimCause
) {

    public static final ResolutionSummary EMPTY =
            new ResolutionSummary(0, null, 0, null, List.of(), List.of());
}
