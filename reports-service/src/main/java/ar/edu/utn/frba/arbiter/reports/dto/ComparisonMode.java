package ar.edu.utn.frba.arbiter.reports.dto;

public enum ComparisonMode {

    /** The stretch of equal length ending the day before the period starts. */
    PREVIOUS_PERIOD,
    SAME_PERIOD_LAST_YEAR,
    /** It need not be as long as the period. */
    CUSTOM
}
