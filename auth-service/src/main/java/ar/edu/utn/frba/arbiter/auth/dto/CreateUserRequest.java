package ar.edu.utn.frba.arbiter.auth.dto;

import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record CreateUserRequest(
        @NotBlank @Email String email,
        @NotBlank String nombre,
        @NotBlank String apellido,
        @NotBlank String password,
        @NotNull UserRole rol,
        @NotBlank String sector,
        LocalDate fechaIngreso
) {}
