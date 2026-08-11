package ar.edu.utn.frba.arbiter.rules.dto;

import java.util.List;

/**
 * Forma del {@code configuration} (JSONB) de una regla {@code COVERAGE_EXCLUSION}: la lista negra de
 * hechos generadores que la cobertura NO cubre. Lista negra a propósito (no blanca): una cobertura
 * sin regla se comporta igual que hoy —acepta todo—, así que agregar exclusiones es aditivo y no
 * rompe el flujo existente.
 */
public record CoverageExclusionConfig(List<Long> excludedClaimCauseIds) {

    public List<Long> excludedClaimCauseIds() {
        return excludedClaimCauseIds == null ? List.of() : excludedClaimCauseIds;
    }
}
