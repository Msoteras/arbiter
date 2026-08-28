package ar.edu.utn.frba.arbiter.auth.dto;

import ar.edu.utn.frba.arbiter.common.enums.UserRole;

import java.time.Instant;

public record LoginResponse(
        String token,
        Instant expiresAt,
        /** Id of the logged-in user. The front resolves what's "mine" against it — the bandeja's
            "Míos" lens and the "Tomar" shortcut. */
        Long id,
        String email,
        UserRole rol,
        String nombre,
        String apellido,
        /** The insured's DNI (null for analista/referente). The front uses it for the portal
            without asking again — see User.insuredId. */
        String insuredId,
        /** Whether the insured completed the first-login onboarding (null for non-ASEGURADO). */
        Boolean onboardingComplete
) {}
