package ar.edu.utn.frba.arbiter.auth.dto;

public record UpdateProfileRequest(
        String email,
        String phone,
        Boolean imageConsent,
        String imageConsentVersion
) {}
