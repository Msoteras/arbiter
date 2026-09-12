package ar.edu.utn.frba.arbiter.reports.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the referent's dashboard draws, for one insurer and one period, in a single response:
 * the funnel, the KPI cards, the three distributions and the timeline. One request because the
 * panel shows it all at once — six endpoints would mean six round trips to paint one screen, and a
 * period selector that repaints it piecemeal.
 *
 * <p>The insurer is never a parameter. It's the schema the caller's JWT resolves to, so there is no
 * way to ask for another company's numbers (acceptance criterion 1).
 *
 * @param from             first day of the period, included
 * @param to               last day of the period, included
 * @param granularity      how wide each {@link #timeline()} point is; derived from the period and
 *                         from how much actually happened in it
 * @param filter           the cuts this response was computed under, echoed back so the screen can
 *                         tell "no claims" from "no claims matching this filter"
 * @param funnel           the period's intake, followed forward — a different population from
 *                         {@link #summary()}; see {@link IntakeFunnel}
 * @param previousSummary  the same summary over the period immediately before this one, of equal
 *                         length. It's what turns every figure from a number into a direction. Null
 *                         only if it could not be computed.
 * @param agreement        how often the analyst decided where the model pointed
 * @param resolutionTarget the insurer's own service goal and how many decisions ran past it. Not
 *                         the legal deadline: that one is per claim and missing it is a different,
 *                         worse problem.
 * @param byStatus         claims filed in the period, by the status they sit in <b>now</b> — a
 *                         snapshot of where the intake ended up, not of transitions
 * @param byBranch         claims filed in the period, by branch ("ramo")
 * @param byClassification claims filed in the period, by classification
 * @param byRiskBand       claims filed in the period, by risk band
 */
public record ClaimMetrics(
        LocalDate from,
        LocalDate to,
        Instant generatedAt,
        TimelineGranularity granularity,
        MetricsFilter filter,
        IntakeFunnel funnel,
        MetricsSummary summary,
        MetricsSummary previousSummary,
        RecommendationAgreement agreement,
        ResolutionTarget resolutionTarget,
        List<MetricCount> byStatus,
        List<MetricCount> byBranch,
        List<MetricCount> byClassification,
        List<MetricCount> byRiskBand,
        List<TimelinePoint> timeline
) {}
