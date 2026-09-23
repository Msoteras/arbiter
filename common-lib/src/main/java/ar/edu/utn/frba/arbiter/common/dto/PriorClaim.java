package ar.edu.utn.frba.arbiter.common.dto;

import lombok.Builder;

import java.time.LocalDate;

/**
 * A claim the insured already filed through Arbiter. The insurer's {@code siniestro_historico}
 * only has legacy claims, so without these the annual cap and Fast Track would keep counting zero.
 * Same shape as {@code InsuredHistory.ClaimRecord} so both sources merge into one list; there is
 * no settled amount because payment happens outside Arbiter.
 *
 * @param eventDate    when the event happened, not when it was filed: rule windows use the event
 * @param coverageName by name, since that's how the company's records join
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
