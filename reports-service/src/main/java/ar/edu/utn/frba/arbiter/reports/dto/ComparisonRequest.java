package ar.edu.utn.frba.arbiter.reports.dto;

import java.time.LocalDate;

/**
 * @param mode null means {@link ComparisonMode#PREVIOUS_PERIOD}
 * @param from only with {@link ComparisonMode#CUSTOM}
 * @param to   only with {@link ComparisonMode#CUSTOM}
 */
public record ComparisonRequest(ComparisonMode mode, LocalDate from, LocalDate to) {

    public static final ComparisonRequest DEFAULT = new ComparisonRequest(null, null, null);
}
