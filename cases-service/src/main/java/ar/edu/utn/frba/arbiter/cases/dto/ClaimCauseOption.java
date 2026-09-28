package ar.edu.utn.frba.arbiter.cases.dto;

/** A claim cause the case can be moved to, with the coverage that would answer for it. */
public record ClaimCauseOption(Long id, String name, String coverageName) {}
