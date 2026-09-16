package ar.edu.utn.frba.arbiter.reports.dto;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;

import java.time.Instant;
import java.util.List;

/**
 * One flagged case in the fraud report.
 *
 * <p><b>The alert level is {@code riskBand}</b>, the band the scoring engine already wrote on the
 * case — the same one the analyst sees on the case detail's gauge. The report doesn't compute a
 * second scale of its own: the three signals below are largely the score's own inputs
 * ({@code claim_frequency}, {@code image_reuse}, {@code image_web_match} are weighted factors), so
 * a level derived here would double-count them and, worse, could disagree with the gauge about the
 * same case.
 *
 * <p>No numeric score on purpose: the band is what the analyst acts on, and a decimal next to it
 * invites arguing about the number instead of about the signals.
 *
 * @param riskBand         null when the scoring never ran on this case — a Fast Track, or a case
 *                         still being classified. It is still listed if another signal fired, and
 *                         the frontend shows the gauge as "Sin evaluar" rather than as low risk
 * @param signals          which of the three criteria fired, never empty
 * @param claimsInWindow   claims this insured filed in the 12 months up to this one, this one
 *                         included — so 1 means "no others"
 * @param suspiciousImages how many of the case's images the forensic pass flagged
 * @param fraudDetermined  the analyst's determination, not the engine's: the score suggests, a
 *                         person determines
 * @param expertBacked     there is an expert assessment with {@code FRAUD_CONFIRMED} behind it
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
        CaseStatus status,
        boolean fraudDetermined,
        boolean expertBacked
) {}
