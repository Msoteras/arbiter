package ar.edu.utn.frba.arbiter.common.dto;

import lombok.Builder;

import java.time.LocalDate;

/**
 * A claim the insured already filed <b>through Arbiter</b>, travelling with the {@link ClaimReport}
 * so the engine can count it as an antecedent.
 *
 * <p>The insurer's own {@code siniestro_historico} is not enough on its own: it only holds what the
 * company settled in its legacy systems, and every claim filed from here on is born in Arbiter and
 * never goes back. Without these, the annual event cap and the Fast Track's "previous claims"
 * criterion keep reading zero while the insured files one claim after another.
 *
 * <p>Deliberately the same shape the engine already consumes for the company's history
 * ({@code InsuredHistory.ClaimRecord}), so both sources merge into one list and every rule —the
 * annual cap, the coverage-exhaustion check, the prompt— sees them without special-casing where
 * each one came from. What's missing here is what Arbiter genuinely doesn't know: an Arbiter case
 * has no settled amount, because paying is the company's step and it happens outside the platform.
 *
 * @param caseId       the Arbiter case, so a merged record can be traced back to it
 * @param eventDate    when the event happened ({@code cases.occurred_at}), not when it was filed:
 *                     every window in the rules is measured against the event
 * @param policyNumber the policy it was filed against, for the per-policy checks
 * @param branch       rama, to scope the annual cap
 * @param coverageName the coverage that answered, for the exhaustion check. Named and not the id
 *                     because that's what the company's records carry and how the two lists join
 * @param claimCause   hecho generador, for the prompt
 * @param status       the case's status at the time of the read, so the engine can tell an
 *                     in-flight claim from a settled one
 */
@Builder
public record PriorClaim(
        Long caseId,
        LocalDate eventDate,
        String policyNumber,
        String branch,
        String coverageName,
        String claimCause,
        String status
) {}
