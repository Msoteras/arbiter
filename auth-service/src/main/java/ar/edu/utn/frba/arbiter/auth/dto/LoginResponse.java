package ar.edu.utn.frba.arbiter.auth.dto;

import ar.edu.utn.frba.arbiter.common.enums.UserRole;

import java.time.Instant;

public record LoginResponse(
        String token,
        Instant expiresAt,
        Long id,
        String email,
        UserRole rol,
        String nombre,
        String apellido,
        // Null for non-ASEGURADO users.
        String insuredId,
        Boolean onboardingComplete
) {}
