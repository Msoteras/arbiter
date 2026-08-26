package ar.edu.utn.frba.arbiter.auth.dto;

import java.time.Instant;

public record ProfileResponse(
        String name,
        String surname,
        String dni,
        String email,
        String phone,
        boolean pep,
        boolean imageConsent,
        String imageConsentVersion,
        Instant imageConsentAt,
        boolean onboardingComplete
) {}
