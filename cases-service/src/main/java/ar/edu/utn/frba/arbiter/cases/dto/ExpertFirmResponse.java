package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertFirm;

/** {@code branchName} is null for a generalist firm. */
public record ExpertFirmResponse(
        Long id,
        String name,
        String email,
        String zone,
        Long branchId,
        String branchName,
        boolean active,
        ProviderType providerType
) {

    public static ExpertFirmResponse from(ExpertFirm firm) {
        return new ExpertFirmResponse(
                firm.getId(),
                firm.getName(),
                firm.getEmail(),
                firm.getZone(),
                firm.getBranch() != null ? firm.getBranch().getId() : null,
                firm.getBranch() != null ? firm.getBranch().getName() : null,
                firm.isActive(),
                firm.getProviderType()
        );
    }
}
