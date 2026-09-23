package ar.edu.utn.frba.arbiter.cases.dto;

/**
 * Inbox lens counts over the current filters, in one response. {@code open}/{@code closed} don't
 * depend on the active tab, same as {@code all}.
 */
public record LensSummaryResponse(
        long all, long mine, long assigned, long unassigned, long fraud, long open, long closed) {
}
