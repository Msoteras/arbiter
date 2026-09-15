package ar.edu.utn.frba.arbiter.reports.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The cases resolved in a period: the aggregate figures first, then one row per case, oldest
 * resolution first.
 *
 * <p>The filters are echoed back, resolved to what they mean rather than to what was sent — the
 * branch as its name, not its id — so the preview, the exported file and an auditor reading either
 * one all state the same thing about where the numbers came from. A report that doesn't name its
 * own filters can't be told apart from an unfiltered one.
 *
 * @param from       first day of the period, included
 * @param to         last day of the period, included
 * @param branch     the branch ("ramo") filter, by name; null means every branch
 * @param claimCause the claim cause ("hecho generador") filter; null means every cause
 * @param summary    the aggregates over {@link #rows()}, never over a different population
 */
public record ResolutionReport(
        LocalDate from,
        LocalDate to,
        String branch,
        String claimCause,
        Instant generatedAt,
        ResolutionSummary summary,
        List<ResolutionReportRow> rows
) {}
