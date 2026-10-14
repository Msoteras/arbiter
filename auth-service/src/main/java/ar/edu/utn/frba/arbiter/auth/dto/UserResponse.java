package ar.edu.utn.frba.arbiter.auth.dto;

import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import ar.edu.utn.frba.arbiter.common.enums.UserStatus;

import java.time.Instant;

public record UserResponse(
        Long id,
        String email,
        String nombre,
        String apellido,
        UserRole rol,
        UserStatus estado,
        Instant createdAt,
        /** Last successful login; null for an account that never signed in since it is recorded. */
        Instant lastAccessAt
) {}
