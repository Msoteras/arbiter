package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertAssessment;
import ar.edu.utn.frba.arbiter.common.enums.ExpertVerdict;

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
        String verdictNote,
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
                assessment.getVerdictNote(),
                assessment.getReportDocumentId()
        );
    }
}
