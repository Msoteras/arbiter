package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertAssessment;

/**
 * Quién tiene el bien mientras el expediente espera. Es lo único de una derivación que SÍ se le
 * cuenta al asegurado, y solo para el servicio técnico: sin saber a qué taller fue, no puede
 * acercarlo ni preguntar por él. El peritaje sigue siendo invisible para él — ahí nombrar al
 * proveedor delataría la sospecha que motivó la derivación.
 */
public record RepairProviderResponse(String name, String email, String zone) {

    public static RepairProviderResponse from(ExpertAssessment assessment) {
        return new RepairProviderResponse(
                assessment.getExpertName(),
                assessment.getExpertEmail(),
                assessment.getExpertFirm() != null ? assessment.getExpertFirm().getZone() : null);
    }
}
