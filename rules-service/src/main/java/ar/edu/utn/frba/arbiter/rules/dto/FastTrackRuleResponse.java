package ar.edu.utn.frba.arbiter.rules.dto;

/** Confirmación de guardado: la fila de {@code insurer_rule} (FAST_TRACK) tal como quedó en la DB. */
public record FastTrackRuleResponse(Long id, Long branchId, Long coverageId, FastTrackConfigDto config) {}
