package ar.edu.utn.frba.arbiter.siniestros.adapters;

import ar.edu.utn.frba.arbiter.siniestros.dto.BusinessRules;

public interface ReglasAdapter {

    BusinessRules getRules(String branchId, String claimCauseId);
}
