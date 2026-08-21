package ar.edu.utn.frba.arbiter.cases.dto;

/**
 * How many coverages a branch has, for the referente's ramo list — a cheap headcount instead of
 * the full {@code /detailed} payload. Exists because the list previously had nowhere accurate to
 * get this from: it only ever saw a ramo's real coverage count after the referente clicked into
 * it, so an unvisited ramo showed "0 coberturas" even when it had some.
 */
public record CoverageSummary(Long branchId, long coverageCount) {}
