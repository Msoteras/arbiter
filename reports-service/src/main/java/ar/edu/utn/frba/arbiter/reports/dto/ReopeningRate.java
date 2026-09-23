package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * Claims resolved in the period that had been reopened at least once; a decision-quality figure.
 * Counted per case, not per reopening, and over the resolved claims so the denominator matches the
 * summary's other rates.
 *
 * @param resolved lapsed cases included: a reopened lapsed case is just as symptomatic
 * @param rate     null without resolved cases
 */
public record ReopeningRate(long resolved, long reopened, Double rate) {

    public static ReopeningRate of(long resolved, long reopened) {
        return new ReopeningRate(resolved, reopened, resolved == 0 ? null : (double) reopened / resolved);
    }
}
