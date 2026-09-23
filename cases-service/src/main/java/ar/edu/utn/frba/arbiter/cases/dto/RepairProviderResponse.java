package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertAssessment;

/**
 * The only derivation detail shown to the insured, and only for repair services: they need to know
 * where to take the item. Expert assessments stay hidden, since naming the firm would reveal the suspicion.
 */
public record RepairProviderResponse(String name, String email, String zone) {

    public static RepairProviderResponse from(ExpertAssessment assessment) {
        return new RepairProviderResponse(
                assessment.getExpertName(),
                assessment.getExpertEmail(),
                assessment.getExpertFirm() != null ? assessment.getExpertFirm().getZone() : null);
    }
}
