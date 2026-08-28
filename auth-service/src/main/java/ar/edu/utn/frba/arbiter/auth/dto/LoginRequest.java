package ar.edu.utn.frba.arbiter.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * @param password the {@code ARB1.<base64>} envelope the browser seals with the key from
 *                 {@code GET /api/v1/auth/public-key}, not the password itself
 */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {}
