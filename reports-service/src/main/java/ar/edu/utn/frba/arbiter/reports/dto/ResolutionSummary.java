package ar.edu.utn.frba.arbiter.reports.dto;

import java.util.List;

/**
 * The aggregate head of the resolution report, computed from its rows so the two can never disagree.
 *
 * <p>Not the dashboard's numbers: this counts claims <b>closed</b> in the period, the dashboard claims
 * <b>filed</b> in it. Hence {@code fastTrackRate} here is "of what we closed", not "of what came in".
 *
 * @param decidedCases   approved or rejected; the rest lapsed
 * @param averageMinutes over the decided cases only; null when none. Lapsed cases are excluded, as in
 *                       {@link MetricsSummary#averageResolutionHours()}
 * @param averageWaitingMinutes the part spent waiting on third parties, same population
 * @param fastTrackRate  fraction between 0 and 1; null when there is nothing to divide
 */
public record ResolutionSummary(
        long totalCases,
        long decidedCases,
        Double averageMinutes,
        Double averageWaitingMinutes,
        long fastTrackCases,
        Double fastTrackRate,
        List<MetricCount> byStatus,
        List<MetricCount> byClaimCause
) {

    public static final ResolutionSummary EMPTY =
            new ResolutionSummary(0, 0, null, null, 0, null, List.of(), List.of());
}
