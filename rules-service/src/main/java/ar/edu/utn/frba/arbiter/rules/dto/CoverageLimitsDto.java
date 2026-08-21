package ar.edu.utn.frba.arbiter.rules.dto;

/**
 * A coverage's intrinsic limits, evaluated by the engine in code (not the LLM): reporting deadline
 * (D11), event cap per year (D10) and waiting period (D9). They're {@code coverage} columns, edited
 * by the referente in the Coberturas tab. {@code null} = not configured ⇒ the rule isn't evaluated.
 */
public record CoverageLimitsDto(
        Long reportDeadlineHours,
        Integer maxEventsPerYear,
        /**
         * Waiting period: days from the policy's start during which the coverage doesn't apply yet,
         * even though the policy is in force. It exists so insurance isn't bought for an event that
         * already happened or is imminent. {@code null} = no waiting period.
         */
        Integer waitingPeriodDays,
        /**
         * Whether the coverage reaches the cohabiting family group or only the holder (D9). The
         * source is <b>the coverage</b>, not the insurer database's
         * {@code poliza.cubre_grupo_familiar}: both exist and already contradict each other, and
         * the one the referente configures wins (Fede's call, 10/08).
         */
        Boolean coversFamilyGroup,
        /** Whether a settled claim exhausts the coverage for the period (D9). */
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
