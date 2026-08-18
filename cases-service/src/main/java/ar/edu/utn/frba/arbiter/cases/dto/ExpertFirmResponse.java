package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertFirm;

/**
 * A firm the analyst can pick from when deriving. {@code branchName} is null for a generalist,
 * which the frontend renders as "todos los ramos" — the analyst needs to see the difference.
 *
 * <p>The email is exposed: the analyst is the one who deals with the firm, and hiding who the
 * case is about to be sent to would make the choice blind.
 */
public record ExpertFirmResponse(
        Long id,
        String name,
        String email,
        String zone,
        Long branchId,
        String branchName,
        boolean active
) {

    public static ExpertFirmResponse from(ExpertFirm firm) {
        return new ExpertFirmResponse(
                firm.getId(),
                firm.getName(),
                firm.getEmail(),
                firm.getZone(),
                firm.getBranch() != null ? firm.getBranch().getId() : null,
                firm.getBranch() != null ? firm.getBranch().getName() : null,
                firm.isActive()
        );
    }
}
