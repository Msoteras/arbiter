package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.AnalystWorkloadResponse;
import ar.edu.utn.frba.arbiter.cases.dto.AssignedCaseSummaryResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseDocumentResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseFollowUp;
import ar.edu.utn.frba.arbiter.cases.dto.CaseScope;
import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.dto.DocumentAnalysisSummary;
import ar.edu.utn.frba.arbiter.cases.dto.EligibilityCheckRequest;
import ar.edu.utn.frba.arbiter.cases.dto.IntakeDocumentsResponse;
import ar.edu.utn.frba.arbiter.cases.dto.EligibilityCheckResponse;
import ar.edu.utn.frba.arbiter.cases.dto.LensSummaryResponse;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.dto.PolicySnapshotResponse;
import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.dto.RepairProviderResponse;
import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.dto.StatusTransitionResponse;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import ar.edu.utn.frba.arbiter.cases.exceptions.AnalystNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.AnalystProfileNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseAssignedToAnotherAnalystException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotAssignedException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.DocumentNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.DocumentReadException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InsuredIdentityMismatchException;
import ar.edu.utn.frba.arbiter.cases.exceptions.RulesUnavailableException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidAnalystDecisionException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidSettlementException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidStatusTransitionException;
import ar.edu.utn.frba.arbiter.cases.exceptions.MissingRequiredDocumentsException;
import ar.edu.utn.frba.arbiter.cases.exceptions.PolicyInsuredMismatchException;
import ar.edu.utn.frba.arbiter.cases.exceptions.PolicyNotEligibleException;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseSettlement;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicySnapshot;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseAnalysisRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseAnalysisRepository.CaseAnalysis;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentAnalysisRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseLensCountRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseSpecifications;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ExpertAssessmentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import ar.edu.utn.frba.arbiter.common.dto.RuleResultResponse;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.CauseConsistency;
import ar.edu.utn.frba.arbiter.common.enums.SettlementStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.DeadlinePriority;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CaseServiceImpl implements CaseService {

    private final CaseRepository caseRepository;
    private final CaseDocumentRepository caseDocumentRepository;
    private final ExpertAssessmentRepository expertAssessmentRepository;
    private final CaseStatusService caseStatusService;
    private final ClaimsAnalysisClient claimsAnalysisClient;
    private final ClaimsAnalystRepository claimsAnalystRepository;
    private final CaseReferenceResolver referenceResolver;
    private final PolicyEligibilityValidator policyEligibilityValidator;
    private final RulesServiceClient rulesServiceClient;
    private final CaseAnalysisRepository caseAnalysisRepository;
    private final CaseDocumentAnalysisRepository caseDocumentAnalysisRepository;
    private final CaseAccessPolicy accessPolicy;
    private final PolicyCoverageResolver policyCoverageResolver;
    private final InsuredCaseAggregator insuredCaseAggregator;
    private final PolicyTenantLocator policyTenantLocator;
    private final SettlementService settlementService;
    private final UserRepository userRepository;
    private final InsurerRepository insurerRepository;
    private final PolicyService policyService;
    private final InsurerTenantScope tenantScope;
    // Same clock as DeadlineSweepScheduler, so the read model and the sweep agree on priorities.
    private final Clock clock;

    @Override
    public CaseResponse createCase(CaseRequest request, Map<String, MultipartFile> documents) {
        // The insurer comes from the policy, not the login: the token's tenant was fixed before
        // the caller picked a policy, possibly from another insurer.
        String issuingTenant = policyTenantLocator.locate(request.policyNumber());
        String callerTenant = TenantContext.get();
        TenantContext.set(issuingTenant);
        try {
            return createCaseInIssuingTenant(request, documents);
        } finally {
            TenantContext.set(callerTenant);
        }
    }

    /**
     * Enforced server side: a client posting straight to the endpoint must not file without the
     * required documents. Only the first round (what Fast Track requires) is demanded here; the
     * rest is requested at classification time if needed.
     *
     * <p>An unreadable list lets the claim through rather than leaving the insured out over our own
     * outage; the case is marked for {@code DocumentRecheckScheduler}.
     *
     * @return {@code false} when the list couldn't be read, so the case goes in marked
     */
    private boolean verifyRequiredDocuments(
            Long coverageId, String branch, String claimCause, Map<String, MultipartFile> documents) {
        List<String> required = intakeDocumentTypes(coverageId, branch, claimCause);
        if (required == null) {
            return false;
        }
        if (required.isEmpty()) {
            return true;
        }
        Set<String> attached = documents == null ? Set.of() : documents.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        List<String> missing = required.stream().filter(type -> !attached.contains(type)).toList();
        if (!missing.isEmpty()) {
            throw new MissingRequiredDocumentsException(missing);
        }
        return true;
    }

    private CaseResponse createCaseInIssuingTenant(CaseRequest request, Map<String, MultipartFile> documents) {
        // Validated rather than silently overwritten: a mismatch means the client sent something wrong.
        assertFilingOwnDenuncia(request.insuredId());

        // The insured resolves first: an unsynced policy imported from the insurer DB needs a holder.
        Insured insured = referenceResolver.applyDeclaredDetails(
                referenceResolver.resolveInsured(request.insuredId()), request);
        Policy policy = referenceResolver.resolvePolicy(request.policyNumber(), insured.getId());

        // Objects.equals so a policy with an unsynced (null) owner is rejected rather than throwing.
        if (!Objects.equals(insured.getId(), policy.getInsuredId())) {
            throw new PolicyInsuredMismatchException(request.policyNumber());
        }

        // Resolved before the eligibility gate so COVERAGE_EXCLUSION can be checked there too.
        ClaimCause claimCause = referenceResolver.resolveClaimCause(request.branch(), request.claimCause());

        // After the ownership check, so someone else's policy never leaks its coverage window.
        PolicyCoverage contracted = policyCoverageResolver.resolveFor(policy.getId(), claimCause.getId());

        policyEligibilityValidator.validate(
                request.policyNumber(), request.eventDate(), request.policeReportAt(),
                contracted.getCoverage(), claimCause);

        boolean documentsVerified = verifyRequiredDocuments(
                contracted.getCoverage().getId(), request.branch(), request.claimCause(), documents);

        Case entity = Case.builder()
                .claimCause(claimCause)
                .declaredItem(request.insuredItem())
                .insured(insured)
                .policy(policy)
                .coverage(contracted.getCoverage())
                .description(request.description())
                .occurredAt(request.eventDate())
                .policeReportAt(request.policeReportAt())
                .eventAddress(request.eventLocation())
                .province(blankToNull(request.province()))
                .locality(blankToNull(request.locality()))
                .claimedAmount(request.claimedAmount())
                .responseDeadline(LocalDate.now(clock).plusDays(CaseStatusService.RESPONSE_TERM_DAYS))
                .currentStatus(caseStatusService.initialStatus())
                .documentsUnverifiedSince(documentsVerified ? null : Instant.now(clock))
                .build();

        Case saved = caseRepository.save(entity);
        caseStatusService.recordCreation(saved, StatusChangeActor.INSURED, "denuncia registrada");
        storeDocuments(saved.getId(), documents);
        claimsAnalysisClient.analyzeAndPersist(saved, caseDocumentRepository.findByCaseId(saved.getId()));

        // The case may have landed outside the login's tenant; the frontend needs the slug for every
        // later call on it.
        Insurer issuer = insurerRepository.findBySchemaName(TenantContext.get()).orElse(null);
        return toResponse(saved, null, CaseAnalysis.none(),
                issuer == null ? null : InsurerSlug.of(issuer),
                issuer == null ? null : issuer.getName(),
                List.of());
    }

    /**
     * Fast Track's documents, falling back to the full schedule when none is configured.
     *
     * @return {@code null} when rules-service couldn't be read at all
     */
    private List<String> intakeDocumentTypes(Long coverageId, String branch, String claimCause) {
        List<String> fastTrackDocs = rulesServiceClient.fastTrackDocumentTypes(coverageId);
        if (fastTrackDocs == null) {
            return null;
        }
        return fastTrackDocs.isEmpty()
                ? rulesServiceClient.requiredDocumentTypes(branch, claimCause)
                : fastTrackDocs;
    }

    @Override
    public IntakeDocumentsResponse intakeDocuments(String policyNumber, String branch, String claimCause) {
        // Coverage and rules are read in the issuing insurer's schema, as in checkEligibility.
        String issuingTenant = policyTenantLocator.locate(policyNumber);
        String callerTenant = TenantContext.get();
        TenantContext.set(issuingTenant);
        try {
            // Resolved against the token's DNI, so nobody can ask about another person's policy.
            Insured insured = referenceResolver.resolveInsured(CallerContext.get().insuredId());
            Policy policy = referenceResolver.resolvePolicy(policyNumber, insured.getId());
            ClaimCause cause = referenceResolver.resolveClaimCause(branch, claimCause);
            PolicyCoverage contracted = policyCoverageResolver.resolveFor(policy.getId(), cause.getId());

            List<String> fastTrackDocs = rulesServiceClient.fastTrackDocumentTypes(
                    contracted.getCoverage().getId());
            if (fastTrackDocs == null) {
                throw new RulesUnavailableException(new IllegalStateException(
                        "No se pudo leer la documentación requerida para el alta"));
            }
            if (!fastTrackDocs.isEmpty()) {
                return new IntakeDocumentsResponse(fastTrackDocs, true);
            }
            List<String> schedule = rulesServiceClient.requiredDocumentTypes(branch, claimCause);
            if (schedule == null) {
                throw new RulesUnavailableException(new IllegalStateException(
                        "No se pudo leer la agenda documental"));
            }
            return new IntakeDocumentsResponse(schedule, false);
        } finally {
            TenantContext.set(callerTenant);
        }
    }

    @Override
    public EligibilityCheckResponse checkEligibility(EligibilityCheckRequest request) {
        String issuingTenant = policyTenantLocator.locate(request.policyNumber());
        String callerTenant = TenantContext.get();
        TenantContext.set(issuingTenant);
        try {
            return checkEligibilityInIssuingTenant(request);
        } finally {
            TenantContext.set(callerTenant);
        }
    }

    /**
     * Same resolution as {@link #createCaseInIssuingTenant} up to building the case, minus
     * {@code applyDeclaredDetails}: contact details aren't persisted before the claim is submitted.
     */
    private EligibilityCheckResponse checkEligibilityInIssuingTenant(EligibilityCheckRequest request) {
        assertFilingOwnDenuncia(request.insuredId());
        try {
            Insured insured = referenceResolver.resolveInsured(request.insuredId());
            Policy policy = referenceResolver.resolvePolicy(request.policyNumber(), insured.getId());
            if (!Objects.equals(insured.getId(), policy.getInsuredId())) {
                throw new PolicyInsuredMismatchException(request.policyNumber());
            }
            // No claim cause yet at this wizard step: checked against the first contracted coverage,
            // since the term and arrears belong to the policy anyway.
            policyEligibilityValidator.validate(
                    request.policyNumber(), request.eventDate(), request.policeReportAt(),
                    policyCoverageResolver.resolveFor(policy.getId(), null).getCoverage(),
                    null);
            return EligibilityCheckResponse.ok();
        } catch (PolicyNotEligibleException | PolicyInsuredMismatchException e) {
            return EligibilityCheckResponse.notEligible(e.getMessage());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * The DNI comes from the signed token, never the request. A caller with no DNI isn't an
     * insured, so this refuses them too rather than letting them through on a null.
     */
    private void assertFilingOwnDenuncia(String declaredInsuredId) {
        String callerDni = CallerContext.get().insuredId();
        if (callerDni == null || !callerDni.equals(declaredInsuredId)) {
            throw new InsuredIdentityMismatchException();
        }
    }

    @Override
    public CaseResponse addDocumentsAndReclassify(Long caseId, Map<String, MultipartFile> documents) {
        return addDocumentsAndReclassify(caseId, documents, null);
    }

    @Override
    public CaseResponse addDocumentsAndReclassify(Long caseId, Map<String, MultipartFile> documents, String insurerSlug) {
        return tenantScope.forCase(caseId, insurerSlug, () -> {
            // An insured may only add documents to their own case.
            Case entity = readableCase(caseId);

            storeDocuments(caseId, documents);

            // Clear the cached risk so the recalculation window never shows a stale band.
            entity.setRiskScore(null);
            entity.setRiskBand(null);
            entity.setDeterministicFastTrack(false);
            // Fresh cycle: earlier attempts would otherwise fail the case prematurely.
            entity.setClassificationAttempts(0);
            caseStatusService.transition(entity, CaseStatus.PENDING_CLASSIFICATION,
                    StatusChangeActor.INSURED, "documentación adicional subida");

            claimsAnalysisClient.analyzeAndPersist(entity, caseDocumentRepository.findByCaseId(caseId));
            return toResponse(entity);
        });
    }

    @Override
    public CaseResponse retryClassification(Long caseId) {
        Case entity = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));

        // Same reset as addDocumentsAndReclassify: attempts are already at the maximum.
        entity.setRiskScore(null);
        entity.setRiskBand(null);
        entity.setDeterministicFastTrack(false);
        entity.setClassificationAttempts(0);
        // A stale INFRASTRUCTURE reason would make the recovery sweep requeue a later, unrelated failure.
        entity.setClassificationFailureReason(null);
        entity.setClassificationFailureMessage(null);
        // From any state other than CLASSIFICATION_FAILED the state machine answers 409.
        caseStatusService.transition(entity, CaseStatus.PENDING_CLASSIFICATION,
                StatusChangeActor.ANALYST, "reintento manual de clasificación");

        claimsAnalysisClient.analyzeAndPersist(entity, caseDocumentRepository.findByCaseId(caseId));
        return toResponse(entity);
    }

    /**
     * The frontend sends the case JSON itself as a multipart part named "case", which the
     * {@code Map<String, MultipartFile>} binding picks up alongside the documents. It must not be
     * stored or forwarded to OCR as a document.
     */
    private static final String CASE_PAYLOAD_KEY = "case";

    /** Replaces any prior document of the same type. */
    private void storeDocuments(Long caseId, Map<String, MultipartFile> documents) {
        if (documents == null) {
            return;
        }
        documents.forEach((type, file) -> {
            if (CASE_PAYLOAD_KEY.equals(type)) {
                return;
            }
            byte[] content;
            try {
                content = file.getBytes();
            } catch (IOException e) {
                throw new DocumentReadException(type, e);
            }
            CaseDocument document = caseDocumentRepository.findByCaseIdAndType(caseId, type)
                    .orElseGet(() -> CaseDocument.builder().caseId(caseId).type(type).build());
            document.setFilename(file.getOriginalFilename());
            document.setContentType(file.getContentType());
            document.setContent(content);
            caseDocumentRepository.save(document);
        });
    }

    @Override
    public CaseResponse getCase(Long caseId) {
        return getCase(caseId, null);
    }

    @Override
    public CaseResponse getCase(Long caseId, String insurerSlug) {
        return tenantScope.forCase(caseId, insurerSlug, () -> loadCase(caseId));
    }

    private CaseResponse loadCase(Long caseId) {
        Case entity = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));
        accessPolicy.assertCanRead(entity);
        List<StatusTransitionResponse> history = caseStatusService.history(caseId).stream()
                .map(StatusTransitionResponse::from)
                .toList();
        // tenantScope already switched to the case's schema, so this resolves the case's insurer.
        Insurer issuer = insurerRepository.findBySchemaName(TenantContext.get()).orElse(null);
        // No settlement status: the detail fetches the full settlement from its own endpoint.
        return toResponse(entity, history, caseAnalysisRepository.findByCaseId(caseId),
                issuer == null ? null : InsurerSlug.of(issuer),
                issuer == null ? null : issuer.getName(),
                caseDocumentAnalysisRepository.findByCaseId(caseId), traceabilityOf(entity),
                repairProviderOf(entity), null);
    }

    @Override
    public Page<CaseResponse> listCases(List<CaseStatus> status, String claimCause, String policyNumber,
                                         String insuredId, LocalDate eventDateFrom, LocalDate eventDateTo,
                                         String q, RiskBand riskBand, Long analystId, boolean assignedToMe,
                                         boolean unassigned, boolean fraudAlert, boolean assigned,
                                         boolean dueSoon, CaseFollowUp followUp, Integer staleDays,
                                         CaseScope scope, Long insurerId, Pageable pageable) {
        if (accessPolicy.currentUserIsInsured()) {
            // Across all of the insured's insurers; the inbox lenses don't apply to them.
            return toInsuredResponses(insuredCaseAggregator.findOwnCases(
                    status, claimCause, policyNumber, eventDateFrom, eventDateTo, q, riskBand, scope,
                    insurerId, pageable));
        }

        // "Mine" wins over an explicit analyst filter.
        Long ownerId = analystId;
        if (assignedToMe) {
            ownerId = currentAnalystId().orElse(null);
            if (ownerId == null) {
                // No analyst profile in this tenant: empty, never unfiltered.
                return Page.empty(pageable);
            }
        }

        Specification<Case> spec = and(withDueSoon(CaseSpecifications.withFilters(
                status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, q, riskBand,
                ownerId, unassigned, fraudAlert, assigned), dueSoon), CaseSpecifications.scope(scope));
        spec = and(spec, withStale(staleDays));
        spec = and(spec, CaseSpecifications.followUp(followUp));
        return toResponses(caseRepository.findAll(spec, pageable));
    }

    private static Specification<Case> and(Specification<Case> base, Specification<Case> extra) {
        if (extra == null) {
            return base;
        }
        return base == null ? extra : base.and(extra);
    }

    /** Open cases with no change at all in the last {@code staleDays} days. */
    private Specification<Case> withStale(Integer staleDays) {
        if (staleDays == null || staleDays <= 0) {
            return null;
        }
        return CaseSpecifications.staleSince(Instant.now(clock).minus(staleDays, ChronoUnit.DAYS));
    }

    /** Same threshold as the deadline priority ({@code deadlinePriority != NONE}). */
    private Specification<Case> withDueSoon(Specification<Case> base, boolean dueSoon) {
        if (!dueSoon) {
            return base;
        }
        Specification<Case> due = CaseSpecifications.dueSoonBefore(
                LocalDate.now(clock).plusDays(DeadlinePriority.WATCH_DAYS));
        return base == null ? due : base.and(due);
    }

    @Override
    public LensSummaryResponse lensSummary(List<CaseStatus> status, String claimCause, String policyNumber,
                                            String insuredId, LocalDate eventDateFrom, LocalDate eventDateTo,
                                            String q, RiskBand riskBand, Long analystId, CaseFollowUp followUp,
                                            Integer staleDays) {
        // analystId is the referent's filter and a referent has no analyst profile, so it never
        // coexists with a real "me" and both can share the same WHERE.
        Long me = currentAnalystId().orElse(null);
        Specification<Case> spec = CaseSpecifications.withFilters(
                status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, q, riskBand,
                analystId);
        spec = and(spec, withStale(staleDays));
        spec = and(spec, CaseSpecifications.followUp(followUp));

        CaseLensCountRepository.LensCounts counts = caseRepository.countLenses(spec, me);
        LensSummaryResponse.Counts open = toCounts(counts.open());
        LensSummaryResponse.Counts closed = toCounts(counts.closed());
        return new LensSummaryResponse(open, closed, new LensSummaryResponse.Counts(
                open.total() + closed.total(), open.mine() + closed.mine(),
                open.assigned() + closed.assigned(), open.unassigned() + closed.unassigned(),
                open.fraud() + closed.fraud()));
    }

    private static LensSummaryResponse.Counts toCounts(CaseLensCountRepository.OwnershipCounts counts) {
        return new LensSummaryResponse.Counts(
                counts.total(), counts.mine(), counts.assigned(), counts.unassigned(), counts.fraud());
    }

    /** The analyst id is local to the schema, so "me" is resolved here from the token's email. */
    private Optional<Long> currentAnalystId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Optional.empty();
        }
        return claimsAnalystRepository.findByEmail(authentication.getName()).map(ClaimsAnalyst::getId);
    }

    /**
     * Keeps each case's insurer, since ids repeat across schemas. The analysis isn't joined: the
     * insured never sees it, and it would require switching tenant per row.
     */
    private Page<CaseResponse> toInsuredResponses(Page<InsuredCaseAggregator.InsuredCase> page) {
        return page.map(it -> toResponse(it.caseRecord(), null, CaseAnalysis.none(),
                it.insurerSlug(), it.insurerName(), List.of()));
    }

    /** One query per page for the analysis and one for the settlement status, avoiding N+1. */
    Page<CaseResponse> toResponses(Page<Case> page) {
        List<Long> ids = page.getContent().stream().map(Case::getId).toList();
        Map<Long, CaseAnalysis> analyses = caseAnalysisRepository.findByCaseIds(ids);
        Map<Long, SettlementStatus> settlements = settlementService.statusesFor(ids);
        return page.map(entity -> toResponse(entity, null,
                analyses.getOrDefault(entity.getId(), CaseAnalysis.none()),
                null, null, List.of(), Traceability.none(), null,
                settlements.get(entity.getId())));
    }

    @Override
    public CaseResponse assignAnalyst(Long caseId, Long analystId) {
        Case entity = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));

        // Looked up in the active tenant's schema, so another insurer's analyst id simply 404s.
        ClaimsAnalyst analyst = claimsAnalystRepository.findById(analystId)
                .orElseThrow(() -> new AnalystNotFoundException(analystId));

        entity.setAnalyst(analyst);
        caseRepository.save(entity);
        caseStatusService.recordAssignment(entity, accessPolicy.currentAssignmentActor(),
                "expediente asignado a " + fullName(analyst));

        return loadCase(caseId);
    }

    @Override
    public CaseResponse unassignAnalyst(Long caseId) {
        Case entity = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));

        ClaimsAnalyst previous = entity.getAnalyst();
        entity.setAnalyst(null);
        caseRepository.save(entity);
        caseStatusService.recordAssignment(entity, accessPolicy.currentAssignmentActor(), previous == null
                ? "expediente liberado"
                : "expediente liberado (estaba asignado a " + fullName(previous) + ")");

        return loadCase(caseId);
    }

    @Override
    public CaseResponse reopenCase(Long caseId, String reason) {
        Case entity = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));

        // Nothing is reset on purpose: risk and fraud records are facts of the claim, not of the
        // verdict. transition() rejects reopening an open case (409) and restarts the art. 56 term.
        caseStatusService.transition(entity, CaseStatus.PENDING_ANALYST_REVIEW,
                accessPolicy.currentAssignmentActor(), "expediente reabierto: " + reason);

        return loadCase(caseId);
    }

    private static String fullName(ClaimsAnalyst analyst) {
        return analyst.getName() + " " + analyst.getSurname();
    }

    // Closed cases aren't workload. Sorted only so the IN clause is stable across runs.
    private static final List<String> FINAL_STATUS_NAMES = CaseStatusService.TERMINAL_STATUSES.stream()
            .map(Enum::name)
            .sorted()
            .toList();

    private static final List<RiskBand> HIGH_RISK_BANDS = List.of(RiskBand.HIGH, RiskBand.CRITICAL);

    @Override
    public AssignedCaseSummaryResponse assignedCaseSummary() {
        Long analystId = currentAnalystId().orElse(null);
        if (analystId == null) {
            return new AssignedCaseSummaryResponse(0, Map.of(), 0, 0);
        }

        Map<String, Long> byStatus = new HashMap<>();
        long total = 0;
        for (CaseRepository.StatusCount row : caseRepository.countByStatusForAnalyst(analystId)) {
            byStatus.put(row.getStatus(), row.getTotal());
            total += row.getTotal();
        }

        long highRisk = caseRepository.countByAnalystAndRiskBandIn(analystId, HIGH_RISK_BANDS);
        long awaitingReferent = caseRepository.countAwaitingReferentForAnalyst(analystId);
        return new AssignedCaseSummaryResponse(total, byStatus, highRisk, awaitingReferent);
    }

    @Override
    public List<AnalystWorkloadResponse> analystWorkload() {
        Map<Long, Long> activeByAnalyst = new HashMap<>();
        for (CaseRepository.AnalystCaseCount row : caseRepository.countActiveByAnalyst(FINAL_STATUS_NAMES)) {
            activeByAnalyst.put(row.getAnalystId(), row.getTotal());
        }

        // Every analyst of the tenant, those without active cases at zero.
        List<AnalystWorkloadResponse> workload = new ArrayList<>();
        for (ClaimsAnalyst analyst : claimsAnalystRepository.findAll()) {
            workload.add(new AnalystWorkloadResponse(
                    analyst.getId(), fullName(analyst), activeByAnalyst.getOrDefault(analyst.getId(), 0L)));
        }

        workload.sort(Comparator.comparingLong(AnalystWorkloadResponse::activeCases).reversed()
                .thenComparing(AnalystWorkloadResponse::name));
        return workload;
    }

    @Override
    public List<CaseDocumentResponse> getDocuments(Long caseId) {
        return getDocuments(caseId, null);
    }

    @Override
    public List<CaseDocumentResponse> getDocuments(Long caseId, String insurerSlug) {
        return tenantScope.forCase(caseId, insurerSlug, () -> {
            readableCase(caseId);
            return caseDocumentRepository.findByCaseId(caseId).stream()
                    .map(CaseDocumentResponse::from)
                    .toList();
        });
    }

    @Override
    public CaseDocument getDocument(Long caseId, Long documentId) {
        return getDocument(caseId, documentId, null);
    }

    @Override
    public CaseDocument getDocument(Long caseId, Long documentId, String insurerSlug) {
        return tenantScope.forCase(caseId, insurerSlug, () -> {
            readableCase(caseId);
            return caseDocumentRepository.findById(documentId)
                    .filter(doc -> doc.getCaseId().equals(caseId))
                    .orElseThrow(() -> new DocumentNotFoundException(caseId, documentId));
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicyResponse> getInsuredPolicies(Long caseId) {
        // readableCase is the whole authorization: policies are reached through a readable case.
        Case entity = readableCase(caseId);
        return policyService.listByInsured(entity.getInsured().getDni(), false);
    }

    private Case readableCase(Long caseId) {
        Case entity = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));
        accessPolicy.assertCanRead(entity);
        return entity;
    }

    /**
     * {@code @Transactional}: the settlement and the case transition are written together, and an
     * approved case without a settlement (or vice versa) is worse than a failed decision.
     */
    @Override
    @Transactional
    public void recordAnalystDecision(Long caseId, AnalystDecisionRequest request) {
        Case entity = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));

        CaseStatus targetStatus = switch (request.decision() == null ? "" : request.decision().trim().toUpperCase()) {
            case "APPROVE", "APROBAR" -> CaseStatus.APPROVED;
            case "REJECT", "RECHAZAR" -> CaseStatus.REJECTED;
            default -> throw new InvalidAnalystDecisionException(request.decision());
        };

        // The decision lands in classification-service's immutable audit log; validate the
        // transition BEFORE forwarding so an unreviewable case never gets a decision recorded.
        if (entity.getStatus() != CaseStatus.PENDING_ANALYST_REVIEW) {
            throw new InvalidStatusTransitionException(entity.getStatus(), targetStatus);
        }

        // Resolved from the JWT, never the body, so a decision can't be attributed to someone else.
        String callerEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        ClaimsAnalyst analyst = claimsAnalystRepository.findByEmail(callerEmail)
                .orElseThrow(() -> new AnalystProfileNotFoundException(callerEmail));

        // Only the assigned analyst decides; @PreAuthorize only checks the role. Assign comes
        // first, so a decision never acts as an implicit assignment.
        if (entity.getAnalyst() == null) {
            throw new CaseNotAssignedException(caseId);
        }
        if (!entity.getAnalyst().getId().equals(analyst.getId())) {
            throw new CaseAssignedToAnotherAnalystException(caseId);
        }

        // Approving includes the amount. Validated before forwarding anything, so a decision that
        // is going to fail never reaches the audit log.
        if (targetStatus == CaseStatus.APPROVED && request.settlement() == null) {
            throw InvalidSettlementException.missing();
        }
        if (targetStatus == CaseStatus.REJECTED && request.settlement() != null) {
            throw InvalidSettlementException.notApplicable();
        }

        // Settlement first: above the analyst's authority the approval isn't recorded yet and the
        // case stays put, or a referent return would leave two decisions for one claim.
        if (targetStatus == CaseStatus.APPROVED) {
            CaseSettlement settlement = settlementService.confirm(
                    entity, analyst.getId(), request.justification(), request.settlement());
            if (settlement.getStatus() == SettlementStatus.PENDING_AUTHORIZATION) {
                return;
            }
        }

        resolve(entity, targetStatus, analyst.getId(), request.decision(), request.justification());
    }

    /**
     * Shared by the direct approval and the referent's later authorization, so both leave the
     * same trail.
     */
    private void resolve(Case entity, CaseStatus targetStatus, Long analystId,
                         String decision, String justification) {
        // The attempt count lives here and the audit record keeps its final value. The settlement
        // isn't sent: classification-service audits the verdict, the money stays in this module.
        AnalystDecisionRequest audited = new AnalystDecisionRequest(
                analystId, decision, justification, entity.getClassificationAttempts(), null);

        // The decision row is created there, so its id only exists after the call. Storing it
        // links the case to the model run the verdict was based on.
        entity.setClassificationId(claimsAnalysisClient.forwardAnalystDecision(entity.getId(), audited));

        caseStatusService.transition(entity, targetStatus,
                StatusChangeActor.ANALYST, "decisión del analista: " + decision);
    }

    /**
     * Records the decision with the justification the analyst left held, then approves the case,
     * which triggers the email with the amount.
     */
    @Override
    @Transactional
    public void authorizeSettlement(Long caseId) {
        Case entity = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));

        CaseSettlement pending = settlementService.require(caseId);
        // Read before marking: markAuthorized clears it.
        String justification = pending.getPendingJustification();
        Long analystId = pending.getAnalystId();

        settlementService.markAuthorized(caseId, currentUserId());
        resolve(entity, CaseStatus.APPROVED, analystId, "APPROVE", justification);
    }

    /** No decision to undo: none was recorded while the settlement awaited authorization. */
    @Override
    @Transactional
    public void returnSettlement(Long caseId, String reason) {
        settlementService.returnToAnalyst(caseId, currentUserId(), reason);
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        return userRepository.findByEmail(authentication.getName())
                .map(ar.edu.utn.frba.arbiter.common.models.entities.User::getId)
                .orElse(null);
    }

    /** Freshly created or requeued: no classification to show yet. */
    private CaseResponse toResponse(Case entity) {
        return toResponse(entity, null, CaseAnalysis.none());
    }

    /** The single place the joins are flattened into the shape the frontend speaks. */
    private CaseResponse toResponse(Case entity, List<StatusTransitionResponse> history,
                                     CaseAnalysis analysis) {
        return toResponse(entity, history, analysis, null, null, List.of(), Traceability.none(),
                null, null);
    }

    private CaseResponse toResponse(Case entity, List<StatusTransitionResponse> history,
                                     CaseAnalysis analysis, String insurerSlug, String insurerName,
                                     List<DocumentAnalysisSummary> documentAnalyses) {
        return toResponse(entity, history, analysis, insurerSlug, insurerName, documentAnalyses,
                Traceability.none(), null, null);
    }

    private CaseResponse toResponse(Case entity, List<StatusTransitionResponse> history,
                                     CaseAnalysis analysis, String insurerSlug, String insurerName,
                                     List<DocumentAnalysisSummary> documentAnalyses,
                                     Traceability traceability,
                                     RepairProviderResponse repairProvider,
                                     SettlementStatus settlementStatus) {
        // While reclassifying, the latest llm_analysis row is the previous run: show none.
        CaseAnalysis current = entity.getStatus() == CaseStatus.PENDING_CLASSIFICATION
                ? CaseAnalysis.none()
                : analysis;

        return new CaseResponse(
                entity.getId(),
                insurerSlug,
                insurerName,
                entity.getStatus(),
                entity.getClaimCause().getBranch().getName(),
                entity.getPolicy().getProduct(),
                entity.getClaimCause().getName(),
                entity.getCoverage() == null ? null : entity.getCoverage().getName(),
                entity.getDeclaredItem(),
                entity.getInsured().getDni(),
                entity.getInsured().fullName(),
                entity.getInsured().isPep(),
                entity.getPolicy().getExternalPolicyNumber(),
                entity.getDescription(),
                entity.getOccurredAt(),
                entity.getEventAddress(),
                entity.getClaimedAmount(),
                classificationOf(entity, current),
                confidenceOf(entity, current),
                reasonsOf(entity, current),
                consistencyOf(entity, current),
                wasFastTracked(entity) ? null : current.suggestedClaimCause(),
                wasFastTracked(entity) ? null : current.causeEvidence(),
                entity.getRiskScore(),
                entity.getRiskBand(),
                current.riskBreakdown(),
                entity.getForensicReport(),
                entity.getAnalyst() == null ? null : entity.getAnalyst().getId(),
                entity.getAnalyst() == null ? null : fullName(entity.getAnalyst()),
                entity.getReportedAt(),
                entity.getUpdatedAt(),
                entity.getResponseDeadline(),
                DeadlinePriority.of(entity.getResponseDeadline(), LocalDate.now(clock), isDeadlineInactive(entity)),
                settlementStatus,
                history,
                documentAnalyses,
                traceability.ruleResults(),
                traceability.policySnapshot(),
                repairProvider
        );
    }

    /**
     * Only while the item is at the repair shop, and only in the detail since it costs a query.
     */
    private RepairProviderResponse repairProviderOf(Case entity) {
        if (entity.getStatus() != CaseStatus.PENDING_REPAIR) {
            return null;
        }
        return expertAssessmentRepository
                .findByCaseIdAndProviderType(entity.getId(), ProviderType.SERVICIO_TECNICO)
                .map(RepairProviderResponse::from)
                .orElse(null);
    }

    /** Delegates to {@code CaseStatusService.isDeadlineRunning} so the status lists can't drift. */
    private boolean isDeadlineInactive(Case entity) {
        return !CaseStatusService.isDeadlineRunning(entity.getStatus());
    }

    /**
     * What the analysis tab reads, grouped so list callers don't pass two more empty parameters.
     *
     * @param ruleResults null when they couldn't be read; "none ran" is the empty list
     */
    private record Traceability(List<RuleResultResponse> ruleResults,
                                PolicySnapshotResponse policySnapshot) {

        static Traceability none() {
            return new Traceability(List.of(), null);
        }
    }

    /** Empty for the insured: this is the analyst's audit trail. */
    private Traceability traceabilityOf(Case entity) {
        if (accessPolicy.currentUserIsInsured()) {
            return Traceability.none();
        }
        return new Traceability(
                claimsAnalysisClient.ruleResultsOf(entity.getId()),
                caseRepository.findPolicySnapshot(entity.getId()).map(CaseServiceImpl::snapshotOf)
                        .orElse(null));
    }

    private static PolicySnapshotResponse snapshotOf(PolicySnapshot snapshot) {
        return new PolicySnapshotResponse(
                snapshot.getExternalPolicyNumber(),
                snapshot.getSumInsured(),
                snapshot.isInForce(),
                snapshot.isPaymentsUpToDate(),
                snapshot.getPreviousClaims(),
                snapshot.getTotalAmountClaimed(),
                snapshot.getQueriedAt());
    }

    /**
     * Fast Track leaves no {@code llm_analysis} row, so it is checked first: that table is
     * append-only and would otherwise surface the previous run. The flag is rewritten on every run.
     */
    private Classification classificationOf(Case entity, CaseAnalysis analysis) {
        if (wasFastTracked(entity)) {
            return Classification.FAST_TRACK;
        }
        return analysis.classification();
    }

    private double confidenceOf(Case entity, CaseAnalysis analysis) {
        if (wasFastTracked(entity)) {
            return 1.0;
        }
        return analysis.confidence() != null ? analysis.confidence() : 0.0;
    }

    private List<String> reasonsOf(Case entity, CaseAnalysis analysis) {
        // After a Fast Track, llm_reason belongs to a previous run.
        if (wasFastTracked(entity)) {
            return List.of();
        }
        return analysis.factors();
    }

    private CauseConsistency consistencyOf(Case entity, CaseAnalysis analysis) {
        if (wasFastTracked(entity)) {
            return null;
        }
        return analysis.causeConsistency();
    }

    private boolean wasFastTracked(Case entity) {
        return Boolean.TRUE.equals(entity.getDeterministicFastTrack());
    }
}
