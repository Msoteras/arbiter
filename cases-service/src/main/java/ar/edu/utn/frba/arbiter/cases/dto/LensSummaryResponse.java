package ar.edu.utn.frba.arbiter.cases.dto;

/**
 * Inbox lens counts over the current filters, in one response: one row per lifecycle, each split by
 * ownership, so switching either tab reads another cell instead of asking again.
 */
public record LensSummaryResponse(Counts open, Counts closed, Counts all) {

    public record Counts(long total, long mine, long assigned, long unassigned, long fraud) {
    }
}
