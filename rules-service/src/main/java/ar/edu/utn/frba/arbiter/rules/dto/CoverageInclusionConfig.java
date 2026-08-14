package ar.edu.utn.frba.arbiter.rules.dto;

import java.util.List;

/**
 * Forma del {@code configuration} (JSONB) de una regla {@code COVERAGE_INCLUSION}: la lista de
 * hechos generadores que la cobertura SÍ cubre. Lista blanca a propósito (no negra): una cobertura
 * sin regla no cubre nada — el referente tiene que configurar explícitamente qué cubre cada
 * cobertura, no confiar en un default permisivo (ver docs/decisiones-reglas-a-validar.md).
 */
public record CoverageInclusionConfig(List<Long> includedClaimCauseIds) {

    public List<Long> includedClaimCauseIds() {
        return includedClaimCauseIds == null ? List.of() : includedClaimCauseIds;
    }
}
