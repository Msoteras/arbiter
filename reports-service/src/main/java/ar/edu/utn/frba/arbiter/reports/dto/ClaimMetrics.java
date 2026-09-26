package ar.edu.utn.frba.arbiter.reports.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The whole dashboard in one round trip; the insurer comes from the JWT.
 *
 * @param filter   echoed back so the screen can tell "no claims" from "no claims matching"
 * @param funnel   the period's intake followed forward; not the {@link #summary()} population
 * @param byStatus claims filed in the period by their <b>current</b> status
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
        LegalDeadline legalDeadline,
        ReopeningRate reopening,
        SettledAmounts settled,
        FraudDetection fraud,
        FastTrackImpact fastTrack,
        List<DerivationTurnaround> derivations,
        List<MetricCount> byStatus,
        List<MetricCount> byBranch,
        List<MetricCount> byClassification,
        List<MetricCount> byRiskBand,
        List<MetricCount> byBlockingRule,
        List<TimelinePoint> timeline
) {}
