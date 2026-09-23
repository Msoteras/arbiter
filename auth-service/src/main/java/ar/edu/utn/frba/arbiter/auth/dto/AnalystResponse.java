package ar.edu.utn.frba.arbiter.auth.dto;

/**
 * An analyst a case can be assigned to. {@code id} is {@code claims_analyst}'s (what goes in
 * {@code cases.analyst_id}), not {@code users}', and is only meaningful within its own tenant.
 */
public record AnalystResponse(
        Long id,
        String nombre,
        String apellido,
        String email
) {}
