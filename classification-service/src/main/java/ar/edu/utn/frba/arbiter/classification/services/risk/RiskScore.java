package ar.edu.utn.frba.arbiter.classification.services.risk;

import ar.edu.utn.frba.arbiter.common.dto.RiskBreakdownItem;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;

import java.util.List;

/**
 * When {@code scored} is false the neutral 0.0/LOW must not be persisted or shown as a real LOW band.
 */
public record RiskScore(
        boolean scored,
        double score,
        RiskBand band,
        List<RiskBreakdownItem> breakdown,
        /** Null when the baseline was used, which has no persisted row. */
        Long scoringConfigurationId
) {

    public static RiskScore notScored() {
        return new RiskScore(false, 0.0, RiskBand.LOW, List.of(), null);
    }
}
