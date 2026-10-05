package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.CaseReferral;
import ar.edu.utn.frba.arbiter.common.enums.ExpertVerdict;

import java.math.BigDecimal;
import java.time.Instant;

/** {@code notified} tells whether the email actually left: a provider never told is a case waiting on nobody. */
public record CaseReferralResponse(
        Long id,
        String providerName,
        String providerEmail,
        String zone,
        String reason,
        Instant derivedAt,
        String derivedByName,
        boolean notified,
        Instant reportReceivedAt,
        ExpertVerdict verdict,
        RepairOutcome repairOutcome,
        ProviderType providerType,
        String verdictNote,
        /** Null when the report gave no figure. */
        BigDecimal indemnifiableAmount,
        /** Quoted before the repair, invoiced after. Null if irreparable or the invoice hasn't arrived. */
        BigDecimal repairCost,
        Long reportDocumentId
) {

    public static CaseReferralResponse from(CaseReferral referral) {
        return new CaseReferralResponse(
                referral.getId(),
                referral.getProviderName(),
                referral.getProviderEmail(),
                referral.getProvider() != null ? referral.getProvider().getZone() : null,
                referral.getReason(),
                referral.getDerivedAt(),
                referral.getDerivedBy().getName() + " " + referral.getDerivedBy().getSurname(),
                referral.getNotifiedAt() != null,
                referral.getReportReceivedAt(),
                referral.getVerdict(),
                referral.getRepairOutcome(),
                referral.getProviderType(),
                referral.getVerdictNote(),
                referral.getIndemnifiableAmount(),
                referral.getRepairCost(),
                referral.getReportDocumentId()
        );
    }
}
