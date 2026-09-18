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
 * @param waitingMinutes  the part of that time the case spent waiting on somebody outside the
 *                        insurer — documents from the insured, an expert's report, a repair shop.
 *                        The insurer's own time is the difference. Split because the company's
 *                        procedure says those derivations INTERRUPT the term to pronounce, so
 *                        charging them to the operation measures something nobody there can act on
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
        long waitingMinutes,
        Classification classification,
        String analystDecision,
        CaseStatus finalStatus,
        String analystName
) {}
