package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.DerivationOptionsResponse;
import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.dto.RepairOutcome;
import ar.edu.utn.frba.arbiter.cases.dto.DeriveToExpertRequest;
import ar.edu.utn.frba.arbiter.cases.dto.ExpertAssessmentResponse;
import ar.edu.utn.frba.arbiter.cases.dto.ExpertFirmResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidRepairReportException;
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
 * Referral of a case to an expert or repair shop, and their report. Kept off the decision endpoint:
 * referring isn't a verdict and would blur the immutable decision log. The report doesn't re-run the
 * model either; the analyst decides.
 */
@Service
@RequiredArgsConstructor
public class ExpertAssessmentService {

    /** Unique per (case_id, type), so each provider kind needs its own type or one overwrites the other. */
    static final String REPORT_DOCUMENT_TYPE = "expert_report";
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

    /** Eligible only if the insurer's rule allows it AND the catalog has someone to refer to. */
    @Transactional(readOnly = true)
    public DerivationOptionsResponse options(Long caseId, ProviderType providerType) {
        Case caseRecord = findCase(caseId);
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

    /** Newest first. */
    @Transactional(readOnly = true)
    public List<ExpertAssessmentResponse> findAll(Long caseId) {
        return expertAssessmentRepository.findByCaseIdOrderByDerivedAtDesc(caseId).stream()
                .map(ExpertAssessmentResponse::from)
                .toList();
    }

    /**
     * The state machine guards against a second referral or one on a closed case (409). Only the
     * assigned analyst may refer: it emails an outside firm and parks the case where its owner can
     * no longer decide, so the role check alone isn't enough.
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

        // After the transition, so an expert is never asked about a referral that failed to persist.
        assessment.setNotifiedAt(expertNotificationService.notifyDerivation(caseRecord, assessment));
        return ExpertAssessmentResponse.from(expertAssessmentRepository.save(assessment));
    }

    /**
     * A {@code FRAUD_CONFIRMED} verdict also records fraud on the insured automatically. The optional
     * {@code indemnifiableAmount} only reaches the settlement as a suggestion.
     */
    @Transactional
    public ExpertAssessmentResponse receiveReport(Long caseId, ExpertVerdict verdict, String note,
                                                  BigDecimal indemnifiableAmount, MultipartFile report) {
        Case caseRecord = findCase(caseId);
        ExpertAssessment assessment = awaitingAssessment(caseId, ProviderType.ESTUDIO_LIQUIDADOR);
        assessment.setVerdict(verdict);
        assessment.setIndemnifiableAmount(indemnifiableAmount);
        finishRound(caseRecord, assessment, note, report,
                "informe de peritaje recibido: " + verdict);

        if (verdict == ExpertVerdict.FRAUD_CONFIRMED) {
            fraudRecordService.registerFromExpertReport(caseId, fraudRecordReason(assessment, note));
        }
        return ExpertAssessmentResponse.from(assessment);
    }

    /**
     * No fraud record: a repair investigates nothing. {@code repairCost} (quoted or invoiced) is
     * required for {@code QUOTE_SENT}, optional for {@code REPAIRED} since the invoice may come
     * later, and not allowed for {@code IRREPARABLE}. It becomes the repair formula's accredited amount.
     */
    @Transactional
    public ExpertAssessmentResponse receiveRepairReport(Long caseId, RepairOutcome outcome, String note,
                                                        BigDecimal repairCost, MultipartFile report) {
        Case caseRecord = findCase(caseId);
        boolean charged = repairCost != null && repairCost.signum() > 0;
        if (outcome == RepairOutcome.QUOTE_SENT && !charged) {
            throw InvalidRepairReportException.quoteWithoutAmount(caseId);
        }
        if (outcome == RepairOutcome.IRREPARABLE && charged) {
            throw InvalidRepairReportException.costOnAnIrreparableItem(caseId);
        }
        ExpertAssessment assessment = awaitingAssessment(caseId, ProviderType.SERVICIO_TECNICO);
        assessment.setRepairOutcome(outcome);
        assessment.setRepairCost(charged ? repairCost : null);
        finishRound(caseRecord, assessment, note, report,
                "respuesta del servicio técnico: " + outcome);
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

    /** Only what both provider kinds share; each flow sets its own outcome fields first. */
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

    /** The expert's note goes in whole: it is the evidence, not a summary of it. */
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
     * Enforced server side, not only by hiding the button. "Not enabled" and "amount below
     * threshold" are distinct errors because they mean different things to the analyst.
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

    /** An inactive firm, or one specialised in another branch, is a 404, not a silent referral. */
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

    /** Resolved from the JWT, never the body, so nobody can attribute a referral to someone else. */
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
