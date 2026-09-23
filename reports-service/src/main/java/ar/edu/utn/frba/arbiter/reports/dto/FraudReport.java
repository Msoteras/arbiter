package ar.edu.utn.frba.arbiter.reports.dto;

import ar.edu.utn.frba.arbiter.common.enums.RiskBand;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The cases filed in a period that carry at least one fraud signal, worst alert level first.
 *
 * <p>Anchored to the filing date, unlike {@link ResolutionReport}: the point is the cases someone still
 * has to look at, and anchoring on the resolution would hide exactly those.
 *
 * @param branch          the branch filter by name; null means every branch
 * @param riskBand        null means every band
 * @param previousSummary the same aggregates over the preceding period of equal length, same filters;
 *                        {@link FraudSummary#EMPTY} when it had no claims
 */
public record FraudReport(
        LocalDate from,
        LocalDate to,
        String branch,
        RiskBand riskBand,
        Instant generatedAt,
        FraudSummary summary,
        FraudSummary previousSummary,
        List<FraudReportRow> rows
) {}
