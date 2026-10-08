package ar.edu.utn.frba.arbiter.reports.dto;

import java.time.LocalDate;

/**
 * @param days days whose stored figures were recomputed; fewer than the range when some still have
 *             work in flight and stay unstored
 */
public record MetricsRecalculation(LocalDate from, LocalDate to, int days) {}
