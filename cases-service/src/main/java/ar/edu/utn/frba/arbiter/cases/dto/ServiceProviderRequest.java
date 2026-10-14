package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * @param branchIds the branches it takes cases from; at least one
 * @param active    deactivating retires a provider without erasing the derivations already made to it
 */
public record ServiceProviderRequest(
        @NotBlank(message = "name is required") String name,
        // Email is the only channel to the provider: an invalid one leaves the case waiting on nobody.
        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid address") String email,
        String zone,
        @NotEmpty(message = "Elegí al menos un ramo.") List<Long> branchIds,
        boolean active,
        ProviderType providerType
) {}
