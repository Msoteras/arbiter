package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;

public interface RulesAdapter {

    BusinessRules getRules(String branchId, Long coverageId, String claimCauseId);
}
