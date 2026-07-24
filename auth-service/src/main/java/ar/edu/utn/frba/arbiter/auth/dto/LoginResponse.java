package ar.edu.utn.frba.arbiter.auth.dto;

import ar.edu.utn.frba.arbiter.common.enums.UserRole;

import java.time.Instant;

public record LoginResponse(
        String token,
        Instant expiresAt,
        String email,
        UserRole rol,
        String nombre,
        String apellido,
        /** DNI del asegurado (null para analista/referente). El front lo usa para el portal
            sin volver a pedirlo — ver User.insuredId. */
        String insuredId
) {}
