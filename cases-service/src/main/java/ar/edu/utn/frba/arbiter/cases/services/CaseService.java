package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.AnalystWorkloadResponse;
import ar.edu.utn.frba.arbiter.cases.dto.AssignedCaseSummaryResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseDocumentResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseFollowUp;
import ar.edu.utn.frba.arbiter.cases.dto.CaseScope;
import ar.edu.utn.frba.arbiter.cases.dto.EligibilityCheckRequest;
import ar.edu.utn.frba.arbiter.cases.dto.IntakeDocumentsResponse;
import ar.edu.utn.frba.arbiter.cases.dto.EligibilityCheckResponse;
import ar.edu.utn.frba.arbiter.cases.dto.LensSummaryResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface CaseService {

    CaseResponse createCase(CaseRequest request, Map<String, MultipartFile> documents);

    /**
     * The {@link #createCase} gate without creating anything, so the wizard can block or warn before the
     * upload. Ineligibility is {@code eligible=false}, never an exception.
     */
    EligibilityCheckResponse checkEligibility(EligibilityCheckRequest request);

    /**
     * Fast Track's documents for the coverage, else the full schedule. 503 if the rules engine can't be
     * read: an empty list would mean "nothing needed".
     */
    IntakeDocumentsResponse intakeDocuments(String policyNumber, String branch, String claimCause);

    CaseResponse getCase(Long caseId);

    /** @param insurerSlug only for someone insured at several insurers: case ids repeat across schemas */
    CaseResponse getCase(Long caseId, String insurerSlug);

    List<CaseDocumentResponse> getDocuments(Long caseId);

    /** @param insurerSlug see {@link #getCase(Long, String)} */
    List<CaseDocumentResponse> getDocuments(Long caseId, String insurerSlug);

    CaseDocument getDocument(Long caseId, Long documentId);

    CaseDocument getDocument(Long caseId, Long documentId, String insurerSlug);

    /**
     * Newest first by default; every filter is optional. The tenant schema already bounds it to one
     * insurer.
     *
     * <p>{@code assignedToMe} rather than an analyst id because that id is local to the schema; a caller
     * with no analyst profile gets an empty page, not everything. {@code analystId} is the referent's
     * filter.
     */
    Page<CaseResponse> listCases(List<CaseStatus> status, String claimCause, String policyNumber, String insuredId,
                                  LocalDate eventDateFrom, LocalDate eventDateTo, String q, RiskBand riskBand,
                                  Long analystId, boolean assignedToMe, boolean unassigned, boolean fraudAlert,
                                  boolean assigned, boolean dueSoon, CaseFollowUp followUp, Integer staleDays,
                                  CaseScope scope, Long insurerId, Pageable pageable);

    default Page<CaseResponse> listCases(List<CaseStatus> status, String claimCause, String policyNumber, String insuredId,
                                          LocalDate eventDateFrom, LocalDate eventDateTo, String q, RiskBand riskBand,
                                          boolean assignedToMe, Pageable pageable) {
        return listCases(status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, q, riskBand,
                null, assignedToMe, false, false, false, false, null, null, CaseScope.ALL, null, pageable);
    }

    /** All lens counts at once, over the same filters as the listing, using {@code count(spec)}. */
    LensSummaryResponse lensSummary(List<CaseStatus> status, String claimCause, String policyNumber,
                                     String insuredId, LocalDate eventDateFrom, LocalDate eventDateTo,
                                     String q, RiskBand riskBand, Long analystId, CaseFollowUp followUp,
                                     Integer staleDays);

    CaseResponse addDocumentsAndReclassify(Long caseId, Map<String, MultipartFile> documents);

    /** @param insurerSlug see {@link #getCase(Long, String)} */
    CaseResponse addDocumentsAndReclassify(Long caseId, Map<String, MultipartFile> documents, String insurerSlug);

    /**
     * Manual retry for a case in {@code CLASSIFICATION_FAILED}, which the scheduler no longer
     * sweeps. Resets the attempt counter, otherwise the scheduler would mark it failed again at once.
     */
    CaseResponse retryClassification(Long caseId);

    /**
     * Every policy the case's insured holds. Scoped to the case on purpose: the analyst gets context
     * on whoever they're reviewing, not a lookup by DNI.
     */
    List<PolicyResponse> getInsuredPolicies(Long caseId);

    /**
     * Approving includes the settlement amount: if it exceeds the analyst's authority for the
     * branch, the decision is held — the case doesn't move — until {@link #authorizeSettlement}.
     */
    void recordAnalystDecision(Long caseId, AnalystDecisionRequest request);

    void authorizeSettlement(Long caseId);

    /** The case doesn't move: it never left the analyst's review. */
    void returnSettlement(Long caseId, String reason);

    /** Replaces any previous owner without moving the case; another insurer's analyst id ends in 404. */
    CaseResponse assignAnalyst(Long caseId, Long analystId);

    CaseResponse unassignAnalyst(Long caseId);

    /**
     * Back to {@code PENDING_ANALYST_REVIEW} without touching the previous decision, risk or fraud record.
     * The reason is mandatory: it is the only explanation left in the history.
     */
    CaseResponse reopenCase(Long caseId, String reason);

    /** Every analyst of the tenant with their active case count, zero included, busiest first. */
    List<AnalystWorkloadResponse> analystWorkload();

    /** A caller with no analyst profile gets an empty summary. */
    AssignedCaseSummaryResponse assignedCaseSummary();
}