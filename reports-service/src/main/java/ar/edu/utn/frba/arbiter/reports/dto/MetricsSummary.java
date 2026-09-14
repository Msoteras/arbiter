package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * The KPI cards at the top of the dashboard.
 *
 * <p>Two different populations live here, and mixing them up is the easiest way to read the panel
 * wrong: {@code reported*} counts claims <b>filed</b> in the period, {@code resolved*} counts
 * claims that <b>closed</b> in it — a claim filed in March and closed in April is in one figure of
 * each, and a long period is not the sum of its months for either.
 *
 * <p>Rates are fractions between 0 and 1 (the frontend's percent pipe formats them), and null
 * rather than zero when there is nothing to divide: an insurer that resolved nothing this week has
 * an unknown approval rate, not a 0% one.
 *
 * @param approvalRate            approved over decided (approved + rejected). Lapsed claims are out
 *                                of both ends: nobody approved or rejected them, they ran out of
 *                                time, so counting them would drag the rate down for something the
 *                                analysts never decided.
 * @param fastTrackRate           Fast Track over claims filed in the period. Fast Track is a
 *                                property of how the claim came in, so it reads against the filed
 *                                population, not the resolved one.
 * @param averageResolutionHours  from the claim being filed to the analyst's decision, averaged
 *                                over the claims decided in the period. Lapsed claims are excluded
 *                                on purpose: 18 months of the insured not answering is not
 *                                operational time, and a handful of them would swamp the average.
 * @param averageWaitingHours     the part of that time the file spent waiting on somebody outside
 *                                the insurer — documents from the insured, an expert's report, a
 *                                repair shop. The insurer's own time is the difference between the
 *                                two. It is split because the company's procedure says so: those
 *                                derivations INTERRUPT the legal term to pronounce, so charging
 *                                them to the operation measures something nobody there can act on.
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
