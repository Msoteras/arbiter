package ar.edu.utn.frba.arbiter.rules.dto;

/** Save confirmation: the {@code FAST_TRACK} {@code insurer_rule} row as persisted. */
public record FastTrackRuleResponse(Long id, Long branchId, Long coverageId, FastTrackConfigDto config) {}
