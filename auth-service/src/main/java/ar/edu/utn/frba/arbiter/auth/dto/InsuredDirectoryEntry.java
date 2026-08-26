package ar.edu.utn.frba.arbiter.auth.dto;

/**
 * One policyholder as the insurer's own database has them ({@code aseguradora_*.asegurado}).
 *
 * <p>It is the insurer's record, not something anyone typed into Arbiter: decision #10 makes the
 * company the source of truth for who the insured are, and Arbiter only keeps a local snapshot.
 * That is also why {@code email} can be null here — the company has policyholders it never
 * collected an address for, and those simply cannot be invited.
 */
public record InsuredDirectoryEntry(
        String dni,
        String name,
        String surname,
        String email,
        String phone
) {
}
