package ar.edu.utn.frba.arbiter.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OnboardingRequest(
        String email,
        String phone,
        @NotNull Boolean imageConsent,
        @NotBlank String imageConsentVersion
) {}
