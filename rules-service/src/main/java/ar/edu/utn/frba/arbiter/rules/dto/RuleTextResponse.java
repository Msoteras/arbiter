package ar.edu.utn.frba.arbiter.rules.dto;

import java.util.List;

/** Confirmación de guardado: la fila de {@code insurer_rule} + su contenido, tal como quedó en la DB. */
public record RuleTextResponse(Long id, Long branchId, String ruleType, List<String> items) {}
