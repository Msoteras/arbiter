package ar.edu.utn.frba.arbiter.auth.dto;

import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import ar.edu.utn.frba.arbiter.common.enums.UserStatus;

import java.time.Instant;
import java.time.LocalDate;

public record UserResponse(
        Long id,
        String email,
        String nombre,
        String apellido,
        UserRole rol,
        String sector,
        LocalDate fechaIngreso,
        UserStatus estado,
        Instant createdAt
) {}
