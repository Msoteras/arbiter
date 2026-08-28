package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;

public interface InsurerAdapter {

    InsuredPolicy getPolicy(String policyNumber);

    InsuredHistory getHistory(String insuredId);
}
