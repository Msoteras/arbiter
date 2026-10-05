package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.DerivationOptionsResponse;
import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.dto.RepairOutcome;
import ar.edu.utn.frba.arbiter.cases.dto.CaseReferralRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseReferralResponse;
import ar.edu.utn.frba.arbiter.cases.dto.ServiceProviderResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidRepairReportException;
import ar.edu.utn.frba.arbiter.cases.exceptions.AnalystProfileNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseAssignedToAnotherAnalystException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotAssignedException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.DerivationNotAllowedException;
import ar.edu.utn.frba.arbiter.cases.exceptions.DocumentReadException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseReferralNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.ServiceProviderNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.ReferralReportAlreadyReceivedException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseReferral;
import ar.edu.utn.frba.arbiter.cases.models.entities.ServiceProvider;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseReferralRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ServiceProviderRepository;
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
public class CaseReferralService {

    /** Unique per (case_id, type), so each provider kind needs its own type or one overwrites the other. */
    static final String REPORT_DOCUMENT_TYPE = "expert_report";
    static final String REPAIR_DOCUMENT_TYPE = "repair_report";

    private final CaseRepository caseRepository;
    private final CaseDocumentRepository caseDocumentRepository;
    private final CaseReferralRepository caseReferralRepository;
    private final ServiceProviderRepository serviceProviderRepository;
    private final ClaimsAnalystRepository claimsAnalystRepository;
    private final CaseStatusService caseStatusService;
    private final ReferralNotificationService referralNotificationService;
    private final RulesServiceClient rulesServiceClient;
    private final FraudRecordService fraudRecordService;

    /** Eligible only if the insurer's rule allows it AND the catalog has someone to refer to. */
    @Transactional(readOnly = true)
    public DerivationOptionsResponse options(Long caseId, ProviderType providerType) {
        Case caseRecord = findCase(caseId);
        if (providerType == ProviderType.SERVICIO_TECNICO && !repairAllowed(caseRecord)) {
            return new DerivationOptionsResponse(false, false, null, caseRecord.getClaimedAmount(), List.of());
        }
        List<ServiceProviderResponse> providers = availableProviders(caseRecord, providerType).stream()
                .map(ServiceProviderResponse::from)
                .toList();
        // The amount threshold is the insurer's rule for peritaje; a repair isn't gated by it.
        if (providerType == ProviderType.SERVICIO_TECNICO) {
            return new DerivationOptionsResponse(
                    !providers.isEmpty(), true, null, caseRecord.getClaimedAmount(), providers);
        }
        RulesServiceClient.ExpertDerivationPolicy policy =
                rulesServiceClient.expertDerivationPolicy(branchIdOf(caseRecord));

        boolean allowed = policy.allows(caseRecord.getClaimedAmount());
        return new DerivationOptionsResponse(
                allowed && !providers.isEmpty(),
                allowed,
                policy.minClaimedAmount(),
                caseRecord.getClaimedAmount(),
                providers);
    }

    @Transactional(readOnly = true)
    public Optional<CaseReferralResponse> find(Long caseId, ProviderType providerType) {
        return caseReferralRepository.findByCaseIdAndProviderType(caseId, providerType)
                .map(CaseReferralResponse::from);
    }

    /** Newest first. */
    @Transactional(readOnly = true)
    public List<CaseReferralResponse> findAll(Long caseId) {
        return caseReferralRepository.findByCaseIdOrderByDerivedAtDesc(caseId).stream()
                .map(CaseReferralResponse::from)
                .toList();
    }

    /**
     * The state machine guards against a second referral or one on a closed case (409). Only the
     * assigned analyst may refer: it emails an outside provider and parks the case where its owner can
     * no longer decide, so the role check alone isn't enough.
     */
    @Transactional
    public CaseReferralResponse derive(Long caseId, CaseReferralRequest request,
                                           ProviderType providerType) {
        Case caseRecord = findCase(caseId);
        ClaimsAnalyst caller = assertCallerOwns(caseRecord);
        // Each kind has its own gate: peritaje the amount threshold, repair the claim cause.
        if (providerType == ProviderType.ESTUDIO_LIQUIDADOR) {
            assertInsurerDerivesThisCase(caseRecord);
        } else if (!repairAllowed(caseRecord)) {
            throw new DerivationNotAllowedException(caseRecord.getId(), caseRecord.getClaimCause().getName());
        }
        ServiceProvider provider = availableProvider(caseRecord, request.providerId(), providerType);

        CaseReferral referral = caseReferralRepository.save(CaseReferral.builder()
                .caseId(caseId)
                // Copied, not read through the association: see the entity's javadoc.
                .providerName(provider.getName())
                .providerEmail(provider.getEmail())
                .provider(provider)
                .providerType(providerType)
                .reason(request.reason())
                .derivedBy(caller)
                .build());

        String what = providerType == ProviderType.SERVICIO_TECNICO ? "servicio técnico" : "peritaje";
        caseStatusService.transition(caseRecord, waitingStateFor(providerType),
                StatusChangeActor.ANALYST, "derivado a " + what + ": " + provider.getName());

        // After the transition, so a provider is never asked about a referral that failed to persist.
        referral.setNotifiedAt(referralNotificationService.notifyDerivation(caseRecord, referral));
        return CaseReferralResponse.from(caseReferralRepository.save(referral));
    }

    /**
     * A {@code FRAUD_CONFIRMED} verdict also records fraud on the insured automatically. The optional
     * {@code indemnifiableAmount} only reaches the settlement as a suggestion.
     */
    @Transactional
    public CaseReferralResponse receiveReport(Long caseId, ExpertVerdict verdict, String note,
                                                  BigDecimal indemnifiableAmount, MultipartFile report) {
        Case caseRecord = findCase(caseId);
        CaseReferral referral = awaitingAssessment(caseId, ProviderType.ESTUDIO_LIQUIDADOR);
        referral.setVerdict(verdict);
        referral.setIndemnifiableAmount(indemnifiableAmount);
        finishRound(caseRecord, referral, note, report,
                "informe de peritaje recibido: " + verdict);

        if (verdict == ExpertVerdict.FRAUD_CONFIRMED) {
            fraudRecordService.registerFromExpertReport(caseId, fraudRecordReason(referral, note));
        }
        return CaseReferralResponse.from(referral);
    }

    /**
     * No fraud record: a repair investigates nothing. {@code repairCost} (quoted or invoiced) is
     * required for {@code QUOTE_SENT}, optional for {@code REPAIRED} since the invoice may come
     * later, and not allowed for {@code IRREPARABLE}. It becomes the repair formula's accredited amount.
     */
    @Transactional
    public CaseReferralResponse receiveRepairReport(Long caseId, RepairOutcome outcome, String note,
                                                        BigDecimal repairCost, MultipartFile report) {
        Case caseRecord = findCase(caseId);
        boolean charged = repairCost != null && repairCost.signum() > 0;
        if (outcome == RepairOutcome.QUOTE_SENT && !charged) {
            throw InvalidRepairReportException.quoteWithoutAmount(caseId);
        }
        if (outcome == RepairOutcome.IRREPARABLE && charged) {
            throw InvalidRepairReportException.costOnAnIrreparableItem(caseId);
        }
        CaseReferral referral = awaitingAssessment(caseId, ProviderType.SERVICIO_TECNICO);
        referral.setRepairOutcome(outcome);
        referral.setRepairCost(charged ? repairCost : null);
        finishRound(caseRecord, referral, note, report,
                "respuesta del servicio técnico: " + outcome);
        return CaseReferralResponse.from(referral);
    }

    private CaseReferral awaitingAssessment(Long caseId, ProviderType providerType) {
        CaseReferral referral = caseReferralRepository
                .findByCaseIdAndProviderType(caseId, providerType)
                .orElseThrow(() -> new CaseReferralNotFoundException(caseId));
        // Not the case status: a case already back in PENDING_ANALYST_REVIEW has its report, and
        // the status alone can't tell that from one that was never derived.
        if (!referral.isAwaitingReport()) {
            throw new ReferralReportAlreadyReceivedException(caseId);
        }
        return referral;
    }

    /** Only what both provider kinds share; each flow sets its own outcome fields first. */
    private void finishRound(Case caseRecord, CaseReferral referral, String note,
                             MultipartFile report, String transitionNote) {
        Long caseId = caseRecord.getId();
        String documentType = referral.getProviderType() == ProviderType.SERVICIO_TECNICO
                ? REPAIR_DOCUMENT_TYPE : REPORT_DOCUMENT_TYPE;
        referral.setReportDocumentId(storeReport(caseId, documentType, report).getId());
        referral.setReportReceivedAt(Instant.now());
        referral.setVerdictNote(note);
        caseReferralRepository.save(referral);

        caseStatusService.transition(caseRecord, CaseStatus.PENDING_ANALYST_REVIEW,
                StatusChangeActor.ANALYST, transitionNote);
    }

    /** The expert's note goes in whole: it is the evidence, not a summary of it. */
    private String fraudRecordReason(CaseReferral referral, String note) {
        String header = "Peritaje de " + referral.getProviderName() + ": fraude confirmado.";
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

    /** An inactive provider, or one specialised in another branch, is a 404, not a silent referral. */
    private ServiceProvider availableProvider(Case caseRecord, Long providerId, ProviderType providerType) {
        return availableProviders(caseRecord, providerType).stream()
                .filter(provider -> provider.getId().equals(providerId))
                .findFirst()
                .orElseThrow(() -> new ServiceProviderNotFoundException(providerId));
    }

    private List<ServiceProvider> availableProviders(Case caseRecord, ProviderType providerType) {
        return serviceProviderRepository.findAvailableForBranch(branchIdOf(caseRecord), providerType);
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
