package ar.edu.utn.frba.arbiter.cases.dto;

import java.util.Map;

/**
 * A caller with no analyst profile (the referente) gets an empty summary, not an error.
 *
 * @param byStatus         keyed by {@code CaseStatus} name; only statuses with at least one case appear
 * @param highRisk         cases with riskBand HIGH or CRITICAL
 * @param awaitingReferent cases whose settlement waits for the referente, so no longer pending on the analyst
 */
public record AssignedCaseSummaryResponse(long total, Map<String, Long> byStatus, long highRisk,
                                          long awaitingReferent) {
}
