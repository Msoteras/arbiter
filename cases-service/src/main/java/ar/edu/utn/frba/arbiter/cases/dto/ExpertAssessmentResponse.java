package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertAssessment;
import ar.edu.utn.frba.arbiter.common.enums.ExpertVerdict;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The peritaje as the analyst sees it: who it went to, why, and what came back.
 *
 * <p>{@code notified} says whether the email actually left. A firm that was never told is a case
 * waiting on nobody, and that is invisible unless the screen says so.
 */
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
        /** Lo que el perito determinó que vale el siniestro. Null cuando el informe no puso número. */
        BigDecimal indemnifiableAmount,
        /**
         * Lo que el taller cobra por el trabajo: presupuestado si todavía no lo hizo, facturado si
         * ya lo hizo. Null cuando el equipo era irreparable, o cuando la factura no llegó aún.
         */
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
