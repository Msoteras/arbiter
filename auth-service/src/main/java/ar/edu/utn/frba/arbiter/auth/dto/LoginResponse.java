package ar.edu.utn.frba.arbiter.auth.dto;

import ar.edu.utn.frba.arbiter.common.enums.UserRole;

import java.time.Instant;

public record LoginResponse(
        String token,
        Instant expiresAt,
        /** Id del usuario logueado. El front lo necesita para saber qué es "mío" (ej. la lente
            "Míos" de la bandeja y el atajo "Tomar"), que se resuelven contra este id. */
        Long id,
        String email,
        UserRole rol,
        String nombre,
        String apellido,
        /** DNI del asegurado (null para analista/referente). El front lo usa para el portal
            sin volver a pedirlo — ver User.insuredId. */
        String insuredId
) {}
