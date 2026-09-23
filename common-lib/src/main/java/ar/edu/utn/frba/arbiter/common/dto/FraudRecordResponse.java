package ar.edu.utn.frba.arbiter.common.dto;

import ar.edu.utn.frba.arbiter.common.enums.FraudRecordSource;

import java.time.Instant;

/**
 * A fraud record as read back.
 *
 * @param inForce resolved by classification-service against the insurer's window so no caller
 *                recomputes it. Lapsed records are still returned
 * @param scores  {@code EXPERT_BACKED} and in force; explicit so the UI can say why an alert isn't
 *                moving the gauge
 */
public record FraudRecordResponse(
        Long id,
        String insuredDni,
        Long caseId,
        FraudRecordSource source,
        String reason,
        Long expertAssessmentId,
        String declaredByAnalystName,
        Instant declaredAt,
        boolean inForce,
        boolean scores
) {}
