package ar.edu.utn.frba.arbiter.common.dto;

import ar.edu.utn.frba.arbiter.common.enums.FraudRecordSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Fraud record registration, from cases-service (where the analyst confirms it) to
 * classification-service (which owns it and reads it when scoring the insured's next claim).
 *
 * @param expertAssessmentId     null for {@code ANALYST_DECLARED}
 * @param declaredByAnalystId    resolved by cases-service from the caller's token, never from the client
 * @param declaredByAnalystName  copied, not looked up later: the analyst may have left by the time
 *                               someone reads the record
 */
public record FraudRecordRequest(
        @NotBlank String insuredDni,
        @NotNull Long caseId,
        @NotNull FraudRecordSource source,
        @NotBlank String reason,
        Long expertAssessmentId,
        @NotNull Long declaredByAnalystId,
        @NotBlank String declaredByAnalystName
) {}
