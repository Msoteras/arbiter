package ar.edu.utn.frba.arbiter.rules.dto;

import java.util.List;

/** Confirmación de guardado de la inclusión de una cobertura: la fila de insurer_rule tal como quedó. */
public record CoverageInclusionResponse(
        Long id,
        Long branchId,
        Long coverageId,
        List<Long> includedClaimCauseIds
) {}
