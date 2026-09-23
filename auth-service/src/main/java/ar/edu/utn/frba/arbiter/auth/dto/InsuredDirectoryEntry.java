package ar.edu.utn.frba.arbiter.auth.dto;

/**
 * One policyholder as the insurer's own database has them ({@code aseguradora_*.asegurado}).
 * {@code email} can be null: those policyholders can't be invited.
 */
public record InsuredDirectoryEntry(
        String dni,
        String name,
        String surname,
        String email,
        String phone
) {
}
