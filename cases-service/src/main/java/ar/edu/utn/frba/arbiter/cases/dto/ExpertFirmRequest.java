package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * @param branchId null means a generalist firm, not "unknown"
 * @param active   deactivating retires a firm without erasing the derivations already made to it
 */
public record ExpertFirmRequest(
        @NotBlank(message = "name is required") String name,
        // Email is the only channel to the firm: an invalid one leaves the case waiting on nobody.
        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid address") String email,
        String zone,
        Long branchId,
        boolean active,
        ProviderType providerType
) {}
