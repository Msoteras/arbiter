package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * The claims filed in the period, followed forward even past the period's end. Not the same population
 * as {@link MetricsSummary}'s {@code resolvedCases}, which counts claims <b>closed</b> in the period.
 *
 * @param reported  the denominator of every other step
 * @param analyzed  claims the model looked at; Fast Tracks never reach the model
 * @param stillOpen not in a final status as of now, not as of the period's last day
 */
public record IntakeFunnel(
        long reported,
        long analyzed,
        long decided,
        long fastTrack,
        long stillOpen
) {

    public static final IntakeFunnel EMPTY = new IntakeFunnel(0, 0, 0, 0, 0);
}
