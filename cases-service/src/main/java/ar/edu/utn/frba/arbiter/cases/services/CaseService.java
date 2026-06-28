package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;

public interface CaseService {

    CaseResponse createCase(CaseRequest request);

    CaseResponse getCase(Long caseId);
}
