package ar.edu.utn.frba.arbiter.reports.dto;

import ar.edu.utn.frba.arbiter.common.enums.RiskBand;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The cases filed in a period that carry at least one fraud signal: the aggregate figures first,
 * then one row per case, worst alert level first.
 *
 * <p>Anchored to when the claim was <b>filed</b> and not to when it closed, unlike
 * {@link ResolutionReport}: the point of this one is the cases somebody still has to look at, and
 * anchoring it to the resolution would hide exactly those.
 *
 * <p>The filters are echoed back resolved to what they mean — the branch as its name — so the
 * preview and an auditor reading it state the same thing about where the numbers came from.
 *
 * @param branch   the branch ("ramo") filter, by name; null means every branch
 * @param riskBand the alert-level filter; null means every band
 * @param summary  the aggregates over {@link #rows()}, never over a different population
 */
public record FraudReport(
        LocalDate from,
        LocalDate to,
        String branch,
        RiskBand riskBand,
        Instant generatedAt,
        FraudSummary summary,
        List<FraudReportRow> rows
) {}
