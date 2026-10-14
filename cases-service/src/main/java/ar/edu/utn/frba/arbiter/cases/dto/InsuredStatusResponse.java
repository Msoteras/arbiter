package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.common.enums.InsuredStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.CaseState;

/** @param label {@code case_status.insured_status}, ready to show */
public record InsuredStatusResponse(InsuredStatus code, String label) {

    public static InsuredStatusResponse from(CaseState state) {
        return state == null ? null
                : new InsuredStatusResponse(state.getInsuredStatusCode(), state.getInsuredState());
    }
}
