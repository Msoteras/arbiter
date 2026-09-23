package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * One bar/slice of a distribution chart.
 *
 * @param label null where the dimension does not apply yet (unclassified, unscored). Kept rather than
 *              dropped so the slices still add up to the total.
 */
public record MetricCount(String label, long count) {}
