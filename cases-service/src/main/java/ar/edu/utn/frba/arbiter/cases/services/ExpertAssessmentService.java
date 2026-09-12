package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.DerivationOptionsResponse;
import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.dto.RepairOutcome;
import ar.edu.utn.frba.arbiter.cases.dto.DeriveToExpertRequest;
import ar.edu.utn.frba.arbiter.cases.dto.ExpertAssessmentResponse;
import ar.edu.utn.frba.arbiter.cases.dto.ExpertFirmResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.AnalystProfileNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseAssignedToAnotherAnalystException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotAssignedException;
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
import java.math.BigDecimal;
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
    // Its own type: under the same one, the repair answer would overwrite the expert's report.
    static final String REPAIR_DOCUMENT_TYPE = "repair_report";

    private final CaseRepository caseRepository;
    private final CaseDocumentRepository caseDocumentRepository;
    private final ExpertAssessmentRepository expertAssessmentRepository;
    private final ExpertFirmRepository expertFirmRepository;
    private final ClaimsAnalystRepository claimsAnalystRepository;
    private final CaseStatusService caseStatusService;
    private final ExpertNotificationService expertNotificationService;
    private final RulesServiceClient rulesServiceClient;
    private final FraudRecordService fraudRecordService;

    /**
     * Whether this case can be derived, and to whom. Eligibility is the insurer's rule (the
     * claimed amount against its threshold) AND there being someone to derive to — a policy that
     * allows it with an empty catalog still leaves the analyst nowhere to send it.
     */
    @Transactional(readOnly = true)
    public DerivationOptionsResponse options(Long caseId, ProviderType providerType) {
        Case caseRecord = findCase(caseId);
        // Before the catalog: a stolen phone has nothing to repair, whoever is on file.
        if (providerType == ProviderType.SERVICIO_TECNICO && !repairAllowed(caseRecord)) {
            return new DerivationOptionsResponse(false, null, caseRecord.getClaimedAmount(), List.of());
        }
        List<ExpertFirmResponse> firms = availableFirms(caseRecord, providerType).stream()
                .map(ExpertFirmResponse::from)
                .toList();
        // The amount threshold is the insurer's rule for peritaje; a repair isn't gated by it.
        if (providerType == ProviderType.SERVICIO_TECNICO) {
            return new DerivationOptionsResponse(
                    !firms.isEmpty(), null, caseRecord.getClaimedAmount(), firms);
        }
        RulesServiceClient.ExpertDerivationPolicy policy =
                rulesServiceClient.expertDerivationPolicy(branchIdOf(caseRecord));

        return new DerivationOptionsResponse(
                policy.allows(caseRecord.getClaimedAmount()) && !firms.isEmpty(),
                policy.minClaimedAmount(),
                caseRecord.getClaimedAmount(),
                firms);
    }

    @Transactional(readOnly = true)
    public Optional<ExpertAssessmentResponse> find(Long caseId, ProviderType providerType) {
        return expertAssessmentRepository.findByCaseIdAndProviderType(caseId, providerType)
                .map(ExpertAssessmentResponse::from);
    }

    /** Las derivaciones del expediente, de la más reciente a la más vieja. */
    @Transactional(readOnly = true)
    public List<ExpertAssessmentResponse> findAll(Long caseId) {
        return expertAssessmentRepository.findByCaseIdOrderByDerivedAtDesc(caseId).stream()
                .map(ExpertAssessmentResponse::from)
                .toList();
    }

    /**
     * Derives the case and tells the expert. The state machine is the guard for "can this case be
     * derived": only PENDING_ANALYST_REVIEW leads to PENDING_EXPERT_REPORT, so a second derivation
     * (or one on a closed case) 409s instead of quietly creating a row the unique index would
     * reject later with a 500.
     *
     * <p><b>Only the assigned analyst derives.</b> {@code @PreAuthorize} validates the role, which
     * every analyst in the tenant has; this validates the case. Deriving is not a verdict, but it
     * is not harmless either: it emails an outside firm and parks the case in
     * {@code PENDING_EXPERT_REPORT}, where the analyst who does own it can no longer decide. Same
     * rule and same two exceptions as {@code CaseServiceImpl.recordAnalystDecision} — the check was
     * added there when it turned out any analyst could decide on any case, and derivation, added
     * later, never got it.
     */
    @Transactional
    public ExpertAssessmentResponse derive(Long caseId, DeriveToExpertRequest request,
                                           ProviderType providerType) {
        Case caseRecord = findCase(caseId);
        ClaimsAnalyst caller = assertCallerOwns(caseRecord);
        // Each kind has its own gate: peritaje the amount threshold, repair the claim cause.
        if (providerType == ProviderType.ESTUDIO_LIQUIDADOR) {
            assertInsurerDerivesThisCase(caseRecord);
        } else if (!repairAllowed(caseRecord)) {
            throw new DerivationNotAllowedException(caseRecord.getId(), caseRecord.getClaimCause().getName());
        }
        ExpertFirm firm = availableFirm(caseRecord, request.expertFirmId(), providerType);

        ExpertAssessment assessment = expertAssessmentRepository.save(ExpertAssessment.builder()
                .caseId(caseId)
                // Copied, not read through the association: see the entity's javadoc.
                .expertName(firm.getName())
                .expertEmail(firm.getEmail())
                .expertFirm(firm)
                .providerType(providerType)
                .reason(request.reason())
                .derivedBy(caller)
                .build());

        String what = providerType == ProviderType.SERVICIO_TECNICO ? "servicio técnico" : "peritaje";
        caseStatusService.transition(caseRecord, waitingStateFor(providerType),
                StatusChangeActor.ANALYST, "derivado a " + what + ": " + firm.getName());

        // After the transition: emailing about a derivation that then fails to persist would ask
        // an expert to verify a case that never left the analyst's desk. Same order as
        // CaseStatusService's own notification.
        assessment.setNotifiedAt(expertNotificationService.notifyDerivation(caseRecord, assessment));
        return ExpertAssessmentResponse.from(expertAssessmentRepository.save(assessment));
    }

    /**
     * Files the expert's report and hands the case back to the analyst. The verdict and the
     * document land together — a verdict with no report behind it is a claim, not evidence.
     *
     * <p>A {@code FRAUD_CONFIRMED} verdict also leaves the fraud record on the insured, without a
     * second click: see {@link FraudRecordService#registerFromExpertReport}.
     *
     * <p>{@code indemnifiableAmount} is what the expert put the claim at, and it's optional: not
     * every report ends in a number. It doesn't settle anything on its own — it reaches the
     * settlement as the accredited amount's suggestion, and the analyst still takes it.
     */
    @Transactional
    public ExpertAssessmentResponse receiveReport(Long caseId, ExpertVerdict verdict, String note,
                                                  BigDecimal indemnifiableAmount, MultipartFile report) {
        Case caseRecord = findCase(caseId);
        ExpertAssessment assessment = awaitingAssessment(caseId, ProviderType.ESTUDIO_LIQUIDADOR);
        assessment.setVerdict(verdict);
        assessment.setIndemnifiableAmount(indemnifiableAmount);
        finishRound(caseRecord, assessment, note, report, "informe de peritaje recibido: " + verdict);

        if (verdict == ExpertVerdict.FRAUD_CONFIRMED) {
            fraudRecordService.registerFromExpertReport(caseId, fraudRecordReason(assessment, note));
        }
        return ExpertAssessmentResponse.from(assessment);
    }

    /**
     * La devolución del servicio técnico. Sin antecedente de fraude: una reparación no investiga
     * nada, y el resultado va en su propia columna y no en {@code verdict} por lo mismo.
     */
    @Transactional
    public ExpertAssessmentResponse receiveRepairReport(Long caseId, RepairOutcome outcome, String note,
                                                        MultipartFile report) {
        Case caseRecord = findCase(caseId);
        ExpertAssessment assessment = awaitingAssessment(caseId, ProviderType.SERVICIO_TECNICO);
        assessment.setRepairOutcome(outcome);
        finishRound(caseRecord, assessment, note, report, "respuesta del servicio técnico: " + outcome);
        return ExpertAssessmentResponse.from(assessment);
    }

    private ExpertAssessment awaitingAssessment(Long caseId, ProviderType providerType) {
        ExpertAssessment assessment = expertAssessmentRepository
                .findByCaseIdAndProviderType(caseId, providerType)
                .orElseThrow(() -> new ExpertAssessmentNotFoundException(caseId));
        // Not the case status: a case already back in PENDING_ANALYST_REVIEW has its report, and
        // the status alone can't tell that from one that was never derived.
        if (!assessment.isAwaitingReport()) {
            throw new ExpertReportAlreadyReceivedException(caseId);
        }
        return assessment;
    }

    private void finishRound(Case caseRecord, ExpertAssessment assessment, String note,
                             MultipartFile report, String transitionNote) {
        Long caseId = caseRecord.getId();
        String documentType = assessment.getProviderType() == ProviderType.SERVICIO_TECNICO
                ? REPAIR_DOCUMENT_TYPE : REPORT_DOCUMENT_TYPE;
        assessment.setReportDocumentId(storeReport(caseId, documentType, report).getId());
        assessment.setReportReceivedAt(Instant.now());
        assessment.setVerdictNote(note);
        expertAssessmentRepository.save(assessment);

        caseStatusService.transition(caseRecord, CaseStatus.PENDING_ANALYST_REVIEW,
                StatusChangeActor.ANALYST, transitionNote);
    }

    /**
     * What a colleague reads years from now next to the mark on the person: who found it and what
     * they wrote. The expert's note goes in whole — it is the evidence, not a summary of it.
     */
    private String fraudRecordReason(ExpertAssessment assessment, String note) {
        String header = "Peritaje de " + assessment.getExpertName() + ": fraude confirmado.";
        return note == null || note.isBlank() ? header : header + " " + note.trim();
    }

    private CaseDocument storeReport(Long caseId, String documentType, MultipartFile report) {
        byte[] content;
        try {
            content = report.getBytes();
        } catch (IOException e) {
            throw new DocumentReadException(documentType, e);
        }
        CaseDocument document = caseDocumentRepository
                .findByCaseIdAndType(caseId, documentType)
                .orElseGet(() -> CaseDocument.builder().caseId(caseId).type(documentType).build());
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

    private boolean repairAllowed(Case caseRecord) {
        return rulesServiceClient.repairDerivationPolicy(branchIdOf(caseRecord))
                .allows(caseRecord.getClaimCause().getId());
    }

    /**
     * The firm has to be one this case could actually go to, not just any id in the catalog: an
     * inactive firm, or a specialist in the other branch, is a 404 and not a silent derivation.
     */
    private ExpertFirm availableFirm(Case caseRecord, Long expertFirmId, ProviderType providerType) {
        return availableFirms(caseRecord, providerType).stream()
                .filter(firm -> firm.getId().equals(expertFirmId))
                .findFirst()
                .orElseThrow(() -> new ExpertFirmNotFoundException(expertFirmId));
    }

    private List<ExpertFirm> availableFirms(Case caseRecord, ProviderType providerType) {
        return expertFirmRepository.findAvailableForBranch(branchIdOf(caseRecord), providerType);
    }

    private static CaseStatus waitingStateFor(ProviderType providerType) {
        return providerType == ProviderType.SERVICIO_TECNICO
                ? CaseStatus.PENDING_REPAIR
                : CaseStatus.PENDING_EXPERT_REPORT;
    }

    private Long branchIdOf(Case caseRecord) {
        return caseRecord.getClaimCause().getBranch().getId();
    }

    /**
     * Never off the request body: an analyst id sent by the client would let anyone pin a
     * derivation on someone else. Same mechanism as the decision endpoint.
     */
    /**
     * The caller, once confirmed to be the analyst this case is assigned to. Resolved from the JWT
     * and never from the request body: an id sent by the client would let anyone attribute the
     * derivation to someone else.
     *
     * @return the calling analyst, so the caller doesn't resolve them twice
     */
    private ClaimsAnalyst assertCallerOwns(Case caseRecord) {
        ClaimsAnalyst caller = callerAnalyst();
        if (caseRecord.getAnalyst() == null) {
            throw new CaseNotAssignedException(caseRecord.getId());
        }
        if (!caseRecord.getAnalyst().getId().equals(caller.getId())) {
            throw new CaseAssignedToAnotherAnalystException(caseRecord.getId());
        }
        return caller;
    }

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
