package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * Fast Track versus the rest as two measured averages, deliberately not an estimated saving: with a
 * dozen claims a month, multiplying a difference of averages by a count is noise. Over the claims
 * decided in the period, the same population as the summary's average.
 *
 * @param fastTrackHours null when there is none
 * @param standardHours  null when there is none
 */
public record FastTrackImpact(
        long fastTrackDecided,
        Double fastTrackHours,
        long standardDecided,
        Double standardHours
) {

    public static final FastTrackImpact NONE = new FastTrackImpact(0, null, 0, null);
}
