package ar.edu.utn.frba.arbiter.auth.dto;

/**
 * An analyst a case can be assigned to. Separate from {@code UserResponse} because it answers a
 * different question: not "what accounts exist on the platform" but "who in this insurer can I
 * hand this case to".
 *
 * <p>The {@code id} is {@code claims_analyst}'s, not {@code users}': it's the one that goes in
 * {@code cases.analyst_id}. Being a per-schema table, it <b>only means anything inside the insurer
 * that returned it</b> — don't compare it across tenants.
 */
public record AnalystResponse(
        Long id,
        String nombre,
        String apellido,
        String email
) {}
