package ar.edu.utn.frba.arbiter.common.dto;

import ar.edu.utn.frba.arbiter.common.enums.FraudRecordSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Registration of a fraud record against an insured — cases-service (where the analyst confirms it
 * on a case) to classification-service (which owns the record and reads it while scoring the
 * insured's next claim).
 *
 * <p>The insured travels as their DNI, the same identity {@link ClaimReport#insuredId()} already
 * uses across this boundary: it's unique per tenant and it's what the engine has at hand when the
 * next claim comes in.
 *
 * @param insuredDni             who the record is about
 * @param caseId                 the case it came out of — the record's provenance, and what makes
 *                               it auditable back to a concrete file
 * @param source                 what backs it; only {@code EXPERT_BACKED} ever reaches the engine
 * @param reason                 why the analyst determined the fraud, in their words
 * @param expertAssessmentId     the expert assessment behind an {@code EXPERT_BACKED} record; null
 *                               for {@code ANALYST_DECLARED}
 * @param declaredByAnalystId    the analyst who confirmed it. Never off the client's word —
 *                               cases-service resolves it from the caller's token
 * @param declaredByAnalystName  copied, not looked up later: the record has to stay readable years
 *                               from now, and the analyst may not be at the company by then
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
