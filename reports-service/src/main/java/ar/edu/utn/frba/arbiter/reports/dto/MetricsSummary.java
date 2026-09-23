package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * The dashboard's KPI cards. {@code reported*} counts claims <b>filed</b> in the period and
 * {@code resolved*} claims <b>closed</b> in it: different populations.
 *
 * <p>Rates are fractions between 0 and 1, and null rather than zero when there is nothing to divide.
 *
 * @param approvalRate           approved over decided; lapsed claims are out of both, nobody decided them
 * @param fastTrackRate          over claims filed in the period, since Fast Track is a property of intake
 * @param averageResolutionHours over claims decided in the period; lapsed ones excluded so months of an
 *                               unresponsive insured don't swamp the average
 * @param averageWaitingHours    the part spent waiting on third parties (insured, expert, repair shop),
 *                               which interrupts the legal term; the insurer's own time is the difference
 */
public record MetricsSummary(
        long reportedCases,
        long fastTrackCases,
        long resolvedCases,
        long approvedCases,
        long rejectedCases,
        long lapsedCases,
        Double approvalRate,
        Double rejectionRate,
        Double fastTrackRate,
        Double averageResolutionHours,
        Double averageWaitingHours
) {}
