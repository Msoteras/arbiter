package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.DerivationOptionsResponse;
import ar.edu.utn.frba.arbiter.cases.dto.DeriveToExpertRequest;
import ar.edu.utn.frba.arbiter.cases.dto.ExpertAssessmentResponse;
import ar.edu.utn.frba.arbiter.cases.dto.ExpertFirmResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.AnalystProfileNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.DerivationNotAllowedException;
import ar.edu.utn.frba.arbiter.cases.exceptions.DocumentReadException;
import ar.edu.utn.frba.arbiter.cases.exceptions.ExpertAssessmentNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.ExpertFirmNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.ExpertReportAlreadyReceivedException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertAssessment;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertFirm;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ExpertAssessmentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ExpertFirmRepository;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.ExpertVerdict;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Derivation of a case to an external expert, and the return of their report.
 *
 * <p>Kept out of {@code CaseServiceImpl} and off {@code POST /cases/{id}/decision} on purpose:
 * deriving is <b>not</b> a verdict. The decision endpoint writes to classification-service's
 * immutable audit log, and putting "I need more evidence" in the same row as "I approved this"
 * would blur the record Disposición SSN 2/2023 exists to keep clean.
 *
 * <p>The report does not re-run the model. The expert's finding is evidence produced by a person
 * who inspected the claim; feeding it back to the LLM would either have it restate the report or,
 * worse, contradict it — a model recommendation against expert evidence. The verdict is shown
 * beside the classification and the analyst decides.
 */
@Service
@RequiredArgsConstructor
public class ExpertAssessmentService {

    /** Same key space as every other attachment; the unique (case_id, type) fits one per case. */
    static final String REPORT_DOCUMENT_TYPE = "expert_report";

    private final CaseRepository caseRepository;
    private final CaseDocumentRepository caseDocumentRepository;
    private final ExpertAssessmentRepository expertAssessmentRepository;
    private final ExpertFirmRepository expertFirmRepository;
    private final ClaimsAnalystRepository claimsAnalystRepository;
    private final CaseStatusService caseStatusService;
    private final ExpertNotificationService expertNotificationService;
    private final RulesServiceClient rulesServiceClient;

    /**
     * Whether this case can be derived, and to whom. Eligibility is the insurer's rule (the
     * claimed amount against its threshold) AND there being someone to derive to — a policy that
     * allows it with an empty catalog still leaves the analyst nowhere to send it.
     */
    @Transactional(readOnly = true)
    public DerivationOptionsResponse options(Long caseId) {
        Case caseRecord = findCase(caseId);
        RulesServiceClient.ExpertDerivationPolicy policy =
                rulesServiceClient.expertDerivationPolicy(branchIdOf(caseRecord));
        List<ExpertFirmResponse> firms = availableFirms(caseRecord).stream()
                .map(ExpertFirmResponse::from)
                .toList();

        return new DerivationOptionsResponse(
                policy.allows(caseRecord.getClaimedAmount()) && !firms.isEmpty(),
                policy.minClaimedAmount(),
                caseRecord.getClaimedAmount(),
                firms);
    }

    @Transactional(readOnly = true)
    public Optional<ExpertAssessmentResponse> find(Long caseId) {
        return expertAssessmentRepository.findByCaseId(caseId).map(ExpertAssessmentResponse::from);
    }

    /**
     * Derives the case and tells the expert. The state machine is the guard for "can this case be
     * derived": only PENDING_ANALYST_REVIEW leads to PENDING_EXPERT_REPORT, so a second derivation
     * (or one on a closed case) 409s instead of quietly creating a row the unique index would
     * reject later with a 500.
     */
    @Transactional
    public ExpertAssessmentResponse derive(Long caseId, DeriveToExpertRequest request) {
        Case caseRecord = findCase(caseId);
        assertInsurerDerivesThisCase(caseRecord);
        ExpertFirm firm = availableFirm(caseRecord, request.expertFirmId());

        ExpertAssessment assessment = expertAssessmentRepository.save(ExpertAssessment.builder()
                .caseId(caseId)
                // Copied, not read through the association: see the entity's javadoc.
                .expertName(firm.getName())
                .expertEmail(firm.getEmail())
                .expertFirm(firm)
                .reason(request.reason())
                .derivedBy(callerAnalyst())
                .build());

        caseStatusService.transition(caseRecord, CaseStatus.PENDING_EXPERT_REPORT,
                StatusChangeActor.ANALYST, "derivado a peritaje: " + firm.getName());

        // After the transition: emailing about a derivation that then fails to persist would ask
        // an expert to verify a case that never left the analyst's desk. Same order as
        // CaseStatusService's own notification.
        assessment.setNotifiedAt(expertNotificationService.notifyDerivation(caseRecord, assessment));
        return ExpertAssessmentResponse.from(expertAssessmentRepository.save(assessment));
    }

    /**
     * Files the expert's report and hands the case back to the analyst. The verdict and the
     * document land together — a verdict with no report behind it is a claim, not evidence.
     */
    @Transactional
    public ExpertAssessmentResponse receiveReport(Long caseId, ExpertVerdict verdict, String note,
                                                  MultipartFile report) {
        Case caseRecord = findCase(caseId);
        ExpertAssessment assessment = expertAssessmentRepository.findByCaseId(caseId)
                .orElseThrow(() -> new ExpertAssessmentNotFoundException(caseId));

        // Not the case status: a case that came back to PENDING_ANALYST_REVIEW already has its
        // report, and the status alone can't tell that from one that was never derived.
        if (!assessment.isAwaitingReport()) {
            throw new ExpertReportAlreadyReceivedException(caseId);
        }

        assessment.setReportDocumentId(storeReport(caseId, report).getId());
        assessment.setReportReceivedAt(Instant.now());
        assessment.setVerdict(verdict);
        assessment.setVerdictNote(note);
        expertAssessmentRepository.save(assessment);

        caseStatusService.transition(caseRecord, CaseStatus.PENDING_ANALYST_REVIEW,
                StatusChangeActor.ANALYST, "informe de peritaje recibido: " + verdict);

        return ExpertAssessmentResponse.from(assessment);
    }

    private CaseDocument storeReport(Long caseId, MultipartFile report) {
        byte[] content;
        try {
            content = report.getBytes();
        } catch (IOException e) {
            throw new DocumentReadException(REPORT_DOCUMENT_TYPE, e);
        }
        CaseDocument document = caseDocumentRepository
                .findByCaseIdAndType(caseId, REPORT_DOCUMENT_TYPE)
                .orElseGet(() -> CaseDocument.builder().caseId(caseId).type(REPORT_DOCUMENT_TYPE).build());
        document.setFilename(report.getOriginalFilename());
        document.setContentType(report.getContentType());
        document.setContent(content);
        return caseDocumentRepository.save(document);
    }

    /**
     * The threshold is enforced here and not only by hiding the button: a rule the frontend
     * applies is a suggestion. The two failures are told apart on purpose — "esta aseguradora no
     * deriva este ramo" and "el monto no alcanza" are different answers for the analyst.
     */
    private void assertInsurerDerivesThisCase(Case caseRecord) {
        RulesServiceClient.ExpertDerivationPolicy policy =
                rulesServiceClient.expertDerivationPolicy(branchIdOf(caseRecord));
        if (!policy.enabled()) {
            throw new DerivationNotAllowedException(caseRecord.getId());
        }
        if (!policy.allows(caseRecord.getClaimedAmount())) {
            throw new DerivationNotAllowedException(
                    caseRecord.getId(), caseRecord.getClaimedAmount(), policy.minClaimedAmount());
        }
    }

    /**
     * The firm has to be one this case could actually go to, not just any id in the catalog: an
     * inactive firm, or a specialist in the other branch, is a 404 and not a silent derivation.
     */
    private ExpertFirm availableFirm(Case caseRecord, Long expertFirmId) {
        return availableFirms(caseRecord).stream()
                .filter(firm -> firm.getId().equals(expertFirmId))
                .findFirst()
                .orElseThrow(() -> new ExpertFirmNotFoundException(expertFirmId));
    }

    private List<ExpertFirm> availableFirms(Case caseRecord) {
        return expertFirmRepository.findAvailableForBranch(branchIdOf(caseRecord));
    }

    private Long branchIdOf(Case caseRecord) {
        return caseRecord.getClaimCause().getBranch().getId();
    }

    /**
     * Never off the request body: an analyst id sent by the client would let anyone pin a
     * derivation on someone else. Same mechanism as the decision endpoint.
     */
    private ClaimsAnalyst callerAnalyst() {
        String callerEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        return claimsAnalystRepository.findByEmail(callerEmail)
                .orElseThrow(() -> new AnalystProfileNotFoundException(callerEmail));
    }

    private Case findCase(Long caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));
    }
}
