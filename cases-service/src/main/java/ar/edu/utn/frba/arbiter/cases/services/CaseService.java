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

    /** Lists every case, most recent first. When {@code status} is given, only cases in that status are returned. */
    List<CaseResponse> listCases(CaseStatus status);

    CaseResponse addDocumentsAndReclassify(Long caseId, Map<String, MultipartFile> documents);

    void recordAnalystDecision(Long caseId, AnalystDecisionRequest request);
}