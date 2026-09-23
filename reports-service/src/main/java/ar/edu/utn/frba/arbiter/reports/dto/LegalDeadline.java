package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * Compliance with the art. 56 legal deadline over the claims decided in the period; unlike the
 * resolution target, missing it is a regulatory problem.
 *
 * <p>Nothing is recomputed here: cases-service maintains each case's deadline (derivations freeze it
 * and restart a full 30 days), so the decision date is compared against the deadline it had that day.
 *
 * @param decided lapsed cases excluded: nobody decided them
 * @param rate    null when nothing was decided: unknown, not 0%
 */
public record LegalDeadline(long decided, long onTime, Double rate) {

    public static LegalDeadline of(long decided, long onTime) {
        return new LegalDeadline(decided, onTime, decided == 0 ? null : (double) onTime / decided);
    }
}
