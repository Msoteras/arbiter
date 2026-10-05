package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** No analyst id: it is resolved from the caller's JWT, so nobody can attribute it to someone else. */
public record CaseReferralRequest(
        @NotNull(message = "providerId is required") Long providerId,
        @NotBlank(message = "reason is required") String reason
) {}
