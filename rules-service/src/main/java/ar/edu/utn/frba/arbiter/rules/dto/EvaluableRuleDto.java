package ar.edu.utn.frba.arbiter.rules.dto;

import java.util.List;

/**
 * Una regla dura evaluable por código (no interpretada por el LLM), servida al motor de
 * clasificación. El {@code id} es el de la {@code insurer_rule} y <b>tiene que viajar</b>: es lo
 * que después va a {@code rule_result.rule_id}, sin él no hay auditoría posible (Disposición SSN
 * 2/2023). Hoy el único tipo es {@code COVERAGE_INCLUSION}; el motor matchea el hecho generador del
 * claim contra {@code includedClaimCauseIds} por id — si no está en la lista, la cobertura no lo
 * cubre.
 */
public record EvaluableRuleDto(
        Long id,
        String ruleType,
        String effect,
        boolean blocksFastTrack,
        List<Long> includedClaimCauseIds
) {}
