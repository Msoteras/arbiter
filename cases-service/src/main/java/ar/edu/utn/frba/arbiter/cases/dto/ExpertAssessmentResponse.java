package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertAssessment;
import ar.edu.utn.frba.arbiter.common.enums.ExpertVerdict;

import java.math.BigDecimal;
import java.time.Instant;

/** {@code notified} tells whether the email actually left: a firm never told is a case waiting on nobody. */
public record ExpertAssessmentResponse(
        Long id,
        String expertName,
        String expertEmail,
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

    public static ExpertAssessmentResponse from(ExpertAssessment assessment) {
        return new ExpertAssessmentResponse(
                assessment.getId(),
                assessment.getExpertName(),
                assessment.getExpertEmail(),
                assessment.getExpertFirm() != null ? assessment.getExpertFirm().getZone() : null,
                assessment.getReason(),
                assessment.getDerivedAt(),
                assessment.getDerivedBy().getName() + " " + assessment.getDerivedBy().getSurname(),
                assessment.getNotifiedAt() != null,
                assessment.getReportReceivedAt(),
                assessment.getVerdict(),
                assessment.getRepairOutcome(),
                assessment.getProviderType(),
                assessment.getVerdictNote(),
                assessment.getIndemnifiableAmount(),
                assessment.getRepairCost(),
                assessment.getReportDocumentId()
        );
    }
}
