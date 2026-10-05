package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.CaseReferral;

/**
 * The only derivation detail shown to the insured, and only for repair services: they need to know
 * where to take the item. Expert assessments stay hidden, since naming the provider would reveal the suspicion.
 */
public record RepairProviderResponse(String name, String email, String zone) {

    public static RepairProviderResponse from(CaseReferral referral) {
        return new RepairProviderResponse(
                referral.getProviderName(),
                referral.getProviderEmail(),
                referral.getProvider() != null ? referral.getProvider().getZone() : null);
    }
}
