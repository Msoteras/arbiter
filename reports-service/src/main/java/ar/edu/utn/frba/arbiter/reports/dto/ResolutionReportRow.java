package ar.edu.utn.frba.arbiter.reports.dto;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;

import java.time.Instant;

/**
 * One resolved case in the resolution report.
 *
 * @param reportedAt      when the denuncia was filed
 * @param resolvedAt      when the case last moved into its final status
 * @param totalMinutes    from {@code reportedAt} to {@code resolvedAt}
 * @param classification  {@code FAST_TRACK} when the deterministic gate resolved it, otherwise the
 *                        model's latest recommendation; null when neither ran (e.g. a case that
 *                        lapsed while waiting for documents)
 * @param analystDecision the decision verbatim from {@code case_classification} — APPROVE/REJECT,
 *                        or the older APROBAR/RECHAZAR spelling that some rows still carry; null
 *                        when the case closed with no analyst verdict ({@code LAPSED})
 * @param analystName     whoever decided or, when nobody did, whoever owned the case; null if it
 *                        was never assigned
 */
public record ResolutionReportRow(
        Long caseId,
        String insuredName,
        String insuredDni,
        String branch,
        String claimCause,
        Instant reportedAt,
        Instant resolvedAt,
        long totalMinutes,
        Classification classification,
        String analystDecision,
        CaseStatus finalStatus,
        String analystName
) {}
