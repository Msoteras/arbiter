package ar.edu.utn.frba.arbiter.common.dto;

import ar.edu.utn.frba.arbiter.common.enums.FraudRecordSource;

import java.time.Instant;

/**
 * A fraud record as read back — by cases-service for the analyst reviewing a new claim, and by the
 * referente's panel.
 *
 * <p>{@code inForce} is resolved by classification-service against the insurer's configured window
 * rather than left for the caller to compute: the window is one number in one place, and two
 * modules deciding separately what "vigente" means is how they end up disagreeing on screen.
 *
 * @param inForce whether the record still counts today. A lapsed one is still returned — the
 *                analyst seeing "hubo un antecedente, ya vencido" is different from seeing nothing
 * @param scores  whether this record feeds the risk score and can veto Fast Track, i.e. it is
 *                {@code EXPERT_BACKED} and in force. Sent explicitly so the UI can say why an
 *                alert isn't moving the gauge instead of implying the score ignored it
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
