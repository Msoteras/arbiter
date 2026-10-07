package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.ServiceProvider;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Branch;

import java.util.Comparator;
import java.util.List;

/** {@code branches} is empty for a generalist provider. */
public record ServiceProviderResponse(
        Long id,
        String name,
        String email,
        String zone,
        List<BranchRef> branches,
        boolean active,
        ProviderType providerType
) {

    public record BranchRef(Long id, String name) {}

    public static ServiceProviderResponse from(ServiceProvider provider) {
        return new ServiceProviderResponse(
                provider.getId(),
                provider.getName(),
                provider.getEmail(),
                provider.getZone(),
                provider.getBranches().stream()
                        .sorted(Comparator.comparing(Branch::getName, String.CASE_INSENSITIVE_ORDER))
                        .map(branch -> new BranchRef(branch.getId(), branch.getName()))
                        .toList(),
                provider.isActive(),
                provider.getProviderType()
        );
    }
}
