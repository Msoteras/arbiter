package ar.edu.utn.frba.arbiter.reports.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The cases resolved in a period, oldest resolution first.
 *
 * @param from       first day of the period, included
 * @param to         last day of the period, included
 * @param claimCause the claim cause ("hecho generador") filter; null means every cause
 */
public record ResolutionReport(
        LocalDate from,
        LocalDate to,
        String claimCause,
        Instant generatedAt,
        List<ResolutionReportRow> rows
) {}
