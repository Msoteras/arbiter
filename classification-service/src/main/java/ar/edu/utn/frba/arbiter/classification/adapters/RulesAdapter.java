package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;

public interface RulesAdapter {

    BusinessRules getRules(String branchId, Long coverageId, String claimCauseId);

    /**
     * The insurer's fraud-record policy on its own, without a coverage. {@link #getRules} already
     * carries it inside {@code BusinessRules} for the classification flow; this is for the read
     * paths that have an insured but no claim to hand — the analyst opening a case wants to know
     * whether the record they're looking at is still in force.
     */
    BusinessRules.FraudRecordPolicy getFraudRecordPolicy();
}
