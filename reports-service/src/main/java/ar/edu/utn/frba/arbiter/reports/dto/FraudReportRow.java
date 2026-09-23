package ar.edu.utn.frba.arbiter.reports.dto;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;

import java.time.Instant;
import java.util.List;

/**
 * One flagged case in the fraud report.
 *
 * <p>The alert level is the {@code riskBand} the scoring engine already wrote on the case. The report
 * does not derive its own scale: the signals are largely the score's own inputs, so a second level
 * would double-count them and could disagree with the gauge the analyst sees.
 *
 * @param riskBand                  null when scoring never ran (Fast Track, still classifying)
 * @param signals                   never empty
 * @param claimsInWindow            claims by this insured in the 12 months up to this one, this one
 *                                  included, so 1 means "no others"
 * @param documentInconsistencyNote the {@code document_inconsistency} factor's rationale verbatim; null
 *                                  when it found nothing or did not run
 * @param fraudDetermined           the analyst's determination, not the engine's
 * @param expertBacked              an expert assessment with {@code FRAUD_CONFIRMED} backs it
 */
public record FraudReportRow(
        Long caseId,
        String insuredName,
        String insuredDni,
        String branch,
        String claimCause,
        Instant reportedAt,
        RiskBand riskBand,
        List<FraudSignal> signals,
        int claimsInWindow,
        int suspiciousImages,
        String documentInconsistencyNote,
        CaseStatus status,
        boolean fraudDetermined,
        boolean expertBacked
) {}
