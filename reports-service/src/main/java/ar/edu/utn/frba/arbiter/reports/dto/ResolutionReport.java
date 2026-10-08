package ar.edu.utn.frba.arbiter.reports.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The cases resolved in a period, oldest resolution first.
 *
 * <p>Filters are echoed back resolved (the branch by name) so the preview, the exported file and an
 * auditor all state where the numbers came from.
 *
 * @param branch          null means every branch
 * @param claimCause      null means every cause
 * @param comparisonSummary the same aggregates over the {@code comparison} period, same filters
 */
public record ResolutionReport(
        LocalDate from,
        LocalDate to,
        String branch,
        String claimCause,
        Instant generatedAt,
        ResolutionSummary summary,
        ReportComparison comparison,
        ResolutionSummary comparisonSummary,
        TimelineGranularity granularity,
        List<ResolutionTimelinePoint> timeline,
        List<ResolutionReportRow> rows
) {}
