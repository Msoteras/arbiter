package ar.edu.utn.frba.arbiter.classification.services.risk;

import ar.edu.utn.frba.arbiter.common.dto.RiskBreakdownItem;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;

import java.util.List;

/**
 * Result of scoring a claim: the normalized fraud/risk {@code score} in [0.0, 1.0], the
 * {@link RiskBand} it falls into per the insurer's bands, and the per-factor {@code breakdown}
 * that explains it. The breakdown is what the analyst sees behind the fraud-gauge and what the
 * audit trail keeps — the score is support, never an automatic decision.
 *
 * <p>{@code scored} is {@code false} when there was no scoring config for the branch/claim cause:
 * the engine still returns a neutral 0.0/LOW, but callers must treat it as "sin scorear" and NOT
 * persist/expose it as a real LOW (see {@link #notScored()}).
 */
public record RiskScore(
        boolean scored,
        double score,
        RiskBand band,
        List<RiskBreakdownItem> breakdown
) {

    /** Neutral result for a claim with no scoring config: not scored, 0.0/LOW, empty breakdown. */
    public static RiskScore notScored() {
        return new RiskScore(false, 0.0, RiskBand.LOW, List.of());
    }
}
