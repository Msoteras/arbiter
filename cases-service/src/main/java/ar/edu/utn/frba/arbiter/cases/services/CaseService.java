package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface CaseService {

    CaseResponse createCase(CaseRequest request, Map<String, MultipartFile> documents);

    CaseResponse getCase(Long caseId);

    /**
     * Lists cases, most recent first. Both filters are optional and combinable:
     * {@code status} narrows to one status, {@code insuredId} narrows to one insured's cases
     * (until Auth0 lands, the caller passes it explicitly; then it will come from the JWT).
     */
    List<CaseResponse> listCases(CaseStatus status, String insuredId);

    CaseResponse addDocumentsAndReclassify(Long caseId, Map<String, MultipartFile> documents);

    void recordAnalystDecision(Long caseId, AnalystDecisionRequest request);
}