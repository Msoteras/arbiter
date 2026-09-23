package ar.edu.utn.frba.arbiter.reports.dto;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;

import java.time.Instant;

/**
 * One resolved case in the resolution report.
 *
 * @param resolvedAt      when the case last moved into its final status
 * @param waitingMinutes  the part of {@code totalMinutes} spent waiting on third parties, which
 *                        interrupts the legal term
 * @param classification  {@code FAST_TRACK} from the deterministic gate, else the model's latest
 *                        recommendation; null when neither ran
 * @param analystDecision verbatim from {@code case_classification}: APPROVE/REJECT or the legacy
 *                        APROBAR/RECHAZAR some rows still carry; null for {@code LAPSED}
 * @param analystName     who decided or, if nobody did, who owned the case; null if never assigned
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
