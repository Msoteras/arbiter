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
        Integer waitingPeriodDays
) {

    public static CoverageLimitsDto empty() {
        return new CoverageLimitsDto(null, null, null);
    }

    public boolean isEmpty() {
        return reportDeadlineHours == null && maxEventsPerYear == null && waitingPeriodDays == null;
    }
}
