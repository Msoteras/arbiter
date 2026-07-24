package ar.edu.utn.frba.arbiter.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record ActivateAccountRequest(
        @NotBlank String token,
        @NotBlank String password
) {}
