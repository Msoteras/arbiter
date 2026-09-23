package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * How long each kind of third party (expert firm, repair shop) takes to answer a derivation.
 *
 * <p>Anchored to the derivation date, not the answer date: anchoring on the answer would drop the
 * derivations still pending, which are the ones worth watching.
 *
 * @param providerType raw value as cases-service stores it; the frontend owns the label
 * @param averageHours over the answered ones only; null if none answered. Pending ones are excluded
 *                     because counting their elapsed time would shift the average on every refresh
 */
public record DerivationTurnaround(
        String providerType,
        long derived,
        long answered,
        Double averageHours
) {

    public long pending() {
        return derived - answered;
    }
}
