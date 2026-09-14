package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * One bar/slice of a distribution chart.
 *
 * @param label what the bucket is. Null where the dimension doesn't apply to the case yet — a claim
 *              still being classified has no classification, one the scoring never ran on has no
 *              risk band. The frontend labels those "Sin clasificar" / "Sin evaluar"; leaving them
 *              out instead would make the slices add up to less than the total with no explanation.
 * @param count how many claims fall in it
 */
public record MetricCount(String label, long count) {}
