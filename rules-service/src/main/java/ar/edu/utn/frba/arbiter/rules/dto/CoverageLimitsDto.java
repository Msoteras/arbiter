package ar.edu.utn.frba.arbiter.rules.dto;

/**
 * A coverage's intrinsic limits, evaluated by the engine in code (not the LLM): reporting deadline,
 * event cap per year and waiting period. They're {@code coverage} columns; {@code null} = not
 * configured ⇒ the rule isn't evaluated.
 */
public record CoverageLimitsDto(
        Long reportDeadlineHours,
        Integer maxEventsPerYear,
        /** Days from the policy's start during which the coverage doesn't apply yet. */
        Integer waitingPeriodDays,
        /**
         * Whether the coverage reaches the cohabiting family group or only the holder. The coverage
         * wins over the insurer database's {@code poliza.cubre_grupo_familiar} when they disagree.
         */
        Boolean coversFamilyGroup,
        /** Whether a settled claim exhausts the coverage for the period. */
        Boolean claimExhaustsCoverage
) {

    public static CoverageLimitsDto empty() {
        return new CoverageLimitsDto(null, null, null, null, null);
    }

    public boolean isEmpty() {
        return reportDeadlineHours == null && maxEventsPerYear == null && waitingPeriodDays == null
                && coversFamilyGroup == null && claimExhaustsCoverage == null;
    }
}
