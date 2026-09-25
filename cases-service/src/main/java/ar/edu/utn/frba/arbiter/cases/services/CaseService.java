package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.AnalystWorkloadResponse;
import ar.edu.utn.frba.arbiter.cases.dto.AssignedCaseSummaryResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseDocumentResponse;
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
     * Runs the same gate as {@link #createCase} without creating anything, so the wizard can block
     * or warn before the insured uploads documentation. Ineligibility comes back as
     * {@code eligible=false}, never as an exception.
     */
    EligibilityCheckResponse checkEligibility(EligibilityCheckRequest request);

    /**
     * The first batch of documents for a claim not yet filed: what Fast Track requires for the
     * coverage answering for that claim cause, or the full document schedule if none is configured.
     * Fails with 503 if the rules engine can't be read — an empty list would mean "nothing needed".
     */
    IntakeDocumentsResponse intakeDocuments(String policyNumber, String branch, String claimCause);

    CaseResponse getCase(Long caseId);

    /**
     * @param insurerSlug which of the insured's insurers to look in; only needed for someone insured
     *                    at more than one, since case ids repeat across schemas. Null resolves
     *                    against the login tenant.
     */
    CaseResponse getCase(Long caseId, String insurerSlug);

    List<CaseDocumentResponse> getDocuments(Long caseId);

    /** @param insurerSlug see {@link #getCase(Long, String)} */
    List<CaseDocumentResponse> getDocuments(Long caseId, String insurerSlug);

    CaseDocument getDocument(Long caseId, Long documentId);

    CaseDocument getDocument(Long caseId, Long documentId, String insurerSlug);

    /**
     * Paginated, newest first by default; every filter is optional and combinable. The insurer
     * scope is not a filter: the tenant schema already bounds the listing to one insurer.
     *
     * <p>{@code assignedToMe} is a boolean rather than an analyst id because that id is local to the
     * schema: "me" is resolved from the token, and a caller with no analyst profile gets an empty
     * page, not everything. {@code analystId}, by contrast, is the referent's explicit filter.
     */
    Page<CaseResponse> listCases(List<CaseStatus> status, String claimCause, String policyNumber, String insuredId,
                                  LocalDate eventDateFrom, LocalDate eventDateTo, String q, RiskBand riskBand,
                                  Long analystId, boolean assignedToMe, boolean unassigned, boolean fraudAlert,
                                  boolean assigned, boolean dueSoon, boolean reportReceived, Integer staleDays,
                                  CaseScope scope, Long insurerId, Pageable pageable);

    default Page<CaseResponse> listCases(List<CaseStatus> status, String claimCause, String policyNumber, String insuredId,
                                          LocalDate eventDateFrom, LocalDate eventDateTo, String q, RiskBand riskBand,
                                          boolean assignedToMe, Pageable pageable) {
        return listCases(status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, q, riskBand,
                null, assignedToMe, false, false, false, false, false, null, CaseScope.ALL, null, pageable);
    }

    /** All lens counts at once, over the same filters as the listing, using {@code count(spec)}. */
    LensSummaryResponse lensSummary(List<CaseStatus> status, String claimCause, String policyNumber,
                                     String insuredId, LocalDate eventDateFrom, LocalDate eventDateTo,
                                     String q, RiskBand riskBand, Long analystId, boolean reportReceived,
                                     CaseScope scope);

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

    /**
     * Replaces any previous owner. Assigning never resolves or moves the case. An analyst id from
     * another insurer doesn't resolve in the tenant schema and ends in 404.
     */
    CaseResponse assignAnalyst(Long caseId, Long analystId);

    CaseResponse unassignAnalyst(Long caseId);

    /**
     * Sends a closed case back to {@code PENDING_ANALYST_REVIEW}. Not a new verdict: the previous
     * decision, risk and fraud record stay untouched. The reason is mandatory because it is the
     * only explanation left in the history.
     */
    CaseResponse reopenCase(Long caseId, String reason);

    /** Every analyst of the tenant with their active case count, zero included, busiest first. */
    List<AnalystWorkloadResponse> analystWorkload();

    /** A caller with no analyst profile gets an empty summary. */
    AssignedCaseSummaryResponse assignedCaseSummary();
}