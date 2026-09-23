package ar.edu.utn.frba.arbiter.cases.dto;

/** A cheap per-branch headcount for the referente's branch list, instead of the full {@code /detailed} payload. */
public record CoverageSummary(Long branchId, long coverageCount) {}
