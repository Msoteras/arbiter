package ar.edu.utn.frba.arbiter.common.dto;

import ar.edu.utn.frba.arbiter.common.enums.RiskBand;

/**
 * One document of a claim cause's schedule.
 *
 * @param documentType a code of the branch's catalog
 * @param minRiskBand  null when every case needs it; otherwise only cases from that band up
 */
public record DocumentRequirementItem(String documentType, RiskBand minRiskBand) {

    /** Whether a case in {@code band} needs it. An unscored case needs only the band-free ones. */
    public boolean appliesTo(RiskBand band) {
        return minRiskBand == null || (band != null && band.compareTo(minRiskBand) >= 0);
    }
}
