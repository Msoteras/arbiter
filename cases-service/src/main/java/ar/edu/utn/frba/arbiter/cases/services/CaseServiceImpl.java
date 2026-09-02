package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.AnalystWorkloadResponse;
import ar.edu.utn.frba.arbiter.cases.dto.AssignedCaseSummaryResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseDocumentResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.dto.DocumentAnalysisSummary;
import ar.edu.utn.frba.arbiter.cases.dto.EligibilityCheckRequest;
import ar.edu.utn.frba.arbiter.cases.dto.EligibilityCheckResponse;
import ar.edu.utn.frba.arbiter.cases.dto.LensSummaryResponse;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.dto.PolicySnapshotResponse;
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
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidAnalystDecisionException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidStatusTransitionException;
import ar.edu.utn.frba.arbiter.cases.exceptions.PolicyInsuredMismatchException;
import ar.edu.utn.frba.arbiter.cases.exceptions.PolicyNotEligibleException;
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
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseSpecifications;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import ar.edu.utn.frba.arbiter.common.dto.RuleResultResponse;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CaseServiceImpl implements CaseService {

    private final CaseRepository caseRepository;
    private final CaseDocumentRepository caseDocumentRepository;
    private final CaseStatusService caseStatusService;
    private final ClaimsAnalysisClient claimsAnalysisClient;
    private final ClaimsAnalystRepository claimsAnalystRepository;
    private final CaseReferenceResolver referenceResolver;
    private final PolicyEligibilityValidator policyEligibilityValidator;
    private final CaseAnalysisRepository caseAnalysisRepository;
    private final CaseDocumentAnalysisRepository caseDocumentAnalysisRepository;
    private final CaseAccessPolicy accessPolicy;
    private final PolicyCoverageResolver policyCoverageResolver;
    private final InsuredCaseAggregator insuredCaseAggregator;
    private final PolicyTenantLocator policyTenantLocator;
    private final InsurerRepository insurerRepository;
    private final PolicyService policyService;
    private final InsurerTenantScope tenantScope;
    // Mismo reloj que DeadlineSweepScheduler: la prioridad de vencimiento del read model y la del
    // barrido se computan contra la misma referencia (y es fijable en tests).
    private final Clock clock;

    @Override
    public CaseResponse createCase(CaseRequest request, Map<String, MultipartFile> documents) {
        // La aseguradora sale de la póliza denunciada, no del login: quien tiene pólizas en dos
        // compañías tiene que poder denunciar en cualquiera de las dos, y el tenantSchema del
        // token se fijó cuando todavía no se sabía cuál iba a elegir.
        String issuingTenant = policyTenantLocator.locate(request.policyNumber());
        String callerTenant = TenantContext.get();
        TenantContext.set(issuingTenant);
        try {
            return createCaseInIssuingTenant(request, documents);
        } finally {
            TenantContext.set(callerTenant);
        }
    }

    private CaseResponse createCaseInIssuingTenant(CaseRequest request, Map<String, MultipartFile> documents) {
        // Only the insured files, and only for themselves: the payload's DNI has to be the token's.
        // Without this, insuredId and policyNumber were two independent lookups, so a denuncia could
        // name anyone (D2). Validated rather than silently overwritten — a mismatch means the client
        // is sending something wrong, and correcting it here would hide that.
        assertFilingOwnDenuncia(request.insuredId());

        // Every string in the request has to name something the tenant actually has; anything
        // that doesn't resolve fails with 422 rather than being stored as free text.
        //
        // The insured resolves first because the policy might not be synced yet, and the snapshot
        // pulled from the insurer DB needs someone to point at as its holder.
        Insured insured = referenceResolver.applyDeclaredDetails(
                referenceResolver.resolveInsured(request.insuredId()), request);
        Policy policy = referenceResolver.resolvePolicy(request.policyNumber(), insured.getId());

        // Both resolved on their own; the pairing is what has to hold. Checked after resolving
        // because the policy's owner is an id, and the DNI only becomes one here. Objects.equals
        // so a policy whose owner never synced (null) fails against a real insured instead of
        // throwing: an owner we can't verify is one we don't accept.
        if (!Objects.equals(insured.getId(), policy.getInsuredId())) {
            throw new PolicyInsuredMismatchException(request.policyNumber());
        }

        // Resolved before the eligibility gate so COVERAGE_EXCLUSION can be checked there too
        // (the wizard already filters "¿Qué te pasó?" against it, but that's a UI convenience — a
        // client posting straight to this endpoint isn't stopped by a dropdown).
        ClaimCause claimCause = referenceResolver.resolveClaimCause(request.branch(), request.claimCause());

        // No contract with coverage means no claim to analyze: the case doesn't get created and
        // the insured gets the reason on the spot, instead of waiting for an analyst to close by
        // hand something that was never covered. Runs after the ownership check so an attempt to
        // file against someone else's policy still fails on that and doesn't leak its coverage window.
        // Cuál de las coberturas de la póliza responde por lo denunciado. Antes salía de la póliza
        // (que tenía una sola), así que un hurto sobre una póliza que cubre robo Y hurto se
        // evaluaba contra la cobertura de robo — con su franquicia, su carencia y su plazo.
        PolicyCoverage contracted = policyCoverageResolver.resolveFor(policy.getId(), claimCause.getId());

        policyEligibilityValidator.validate(
                request.policyNumber(), request.eventDate(), request.policeReportAt(),
                contracted.getCoverage(), claimCause);

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
                .build();

        Case saved = caseRepository.save(entity);
        caseStatusService.recordCreation(saved, StatusChangeActor.INSURED, "denuncia registrada");
        storeDocuments(saved.getId(), documents);
        claimsAnalysisClient.analyzeAndPersist(saved, caseDocumentRepository.findByCaseId(saved.getId()));

        // The insured may be a client of more than one company, and the case just landed in
        // whichever one issued the policy (policyTenantLocator), not necessarily the tenant their
        // JWT defaults to. Without insurerSlug on this very first response, the frontend has no way
        // to tell every later call (documents, detail) which schema to look in — they'd all 404 the
        // moment the case landed outside the login's default tenant.
        Insurer issuer = insurerRepository.findBySchemaName(TenantContext.get()).orElse(null);
        return toResponse(saved, null, CaseAnalysis.none(),
                issuer == null ? null : InsurerSlug.of(issuer),
                issuer == null ? null : issuer.getName(),
                List.of());
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
     * Same resolution {@link #createCaseInIssuingTenant} does up to (not including) building the
     * {@code Case} entity — insured, policy, ownership, {@code PolicyEligibilityValidator}. Doesn't
     * call {@code applyDeclaredDetails}: pep/imageConsent/contact fields aren't part of the
     * eligibility question, and persisting them from a value the insured hasn't submitted yet would
     * be premature — that write happens for real at {@link #createCaseInIssuingTenant}.
     */
    private EligibilityCheckResponse checkEligibilityInIssuingTenant(EligibilityCheckRequest request) {
        assertFilingOwnDenuncia(request.insuredId());
        try {
            Insured insured = referenceResolver.resolveInsured(request.insuredId());
            Policy policy = referenceResolver.resolvePolicy(request.policyNumber(), insured.getId());
            if (!Objects.equals(insured.getId(), policy.getInsuredId())) {
                throw new PolicyInsuredMismatchException(request.policyNumber());
            }
            // claimCause null: el precheck corre en el paso 1/2, antes de "¿Qué te pasó?" — no
            // tiene el hecho generador todavía, así que ese chequeo puntual solo se hace en el
            // alta real (createCaseInIssuingTenant), que sí lo tiene siempre.
            // claimCauseId null por lo mismo: sin hecho generador se chequea contra la primera
            // cobertura contratada, que es lo único evaluable en este paso (la vigencia y la mora
            // son de la póliza, no de la cobertura).
            policyEligibilityValidator.validate(
                    request.policyNumber(), request.eventDate(), request.policeReportAt(),
                    policyCoverageResolver.resolveFor(policy.getId(), null).getCoverage(),
                    null);
            return EligibilityCheckResponse.ok();
        } catch (PolicyNotEligibleException | PolicyInsuredMismatchException e) {
            return EligibilityCheckResponse.notEligible(e.getMessage());
        }
    }

    /** Un campo opcional que llega vacío es "no lo cargó", no una cadena vacía guardada en base. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * The denuncia has to be filed by the insured it names. Reads the DNI off the signed token,
     * never off the request — taking it from the payload is exactly what let a denuncia be filed
     * on someone else's behalf.
     *
     * <p>A caller with no DNI in their token is not an ASEGURADO, and only that role can file
     * (the endpoint's {@code @PreAuthorize}), so this also refuses the roles the annotation
     * already blocks rather than letting them through on a null.
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
            // Same ownership check as the reads: an ASEGURADO can only add documents to their own
            // case. This was a plain findById, so anyone could upload to a stranger's case and force
            // it to reclassify (D1). 404 and not 403 for the reason CaseAccessPolicy documents: case
            // ids are sequential, and a 403 would map the table.
            Case entity = readableCase(caseId);

            storeDocuments(caseId, documents);

            // Clear the cached risk so the recalculation window reads as "sin scorear"/recalculando,
            // never a stale band. It's re-populated by the classification poll once the new score
            // lands. The model's recommendation needs no reset: llm_analysis is append-only, and
            // while the case is back in PENDING_CLASSIFICATION toResponse doesn't surface the
            // previous run.
            entity.setRiskScore(null);
            entity.setRiskBand(null);
            // false y no null: la columna es NOT NULL. Para quien lee es lo mismo, wasFastTracked()
            // ya trata los dos igual.
            entity.setDeterministicFastTrack(false);
            // Fresh classification cycle: without this reset, attempts accumulated in previous
            // cycles would push the case to CLASSIFICATION_FAILED prematurely.
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

        // Fresh cycle, same reset as addDocumentsAndReclassify: without zeroing attempts the
        // scheduler would re-fail the case on its next tick (it left CLASSIFICATION_FAILED already
        // at maxAttempts), and the stale risk band would show through the re-classification window.
        entity.setRiskScore(null);
        entity.setRiskBand(null);
        entity.setDeterministicFastTrack(false);
        entity.setClassificationAttempts(0);
        // El motivo describe la corrida anterior, que este reintento deja atrás. Sin limpiarlo, el
        // expediente sigue cargando un INFRASTRUCTURE viejo mientras vuelve a clasificarse — y si la
        // corrida nueva falla por otra cosa, el barrido de recuperación lo ve como recuperable.
        entity.setClassificationFailureReason(null);
        entity.setClassificationFailureMessage(null);
        // transition guards the state machine: only CLASSIFICATION_FAILED → PENDING_CLASSIFICATION
        // is allowed, so a stale retry from any other state 409s instead of silently re-queueing.
        caseStatusService.transition(entity, CaseStatus.PENDING_CLASSIFICATION,
                StatusChangeActor.ANALYST, "reintento manual de clasificación");

        claimsAnalysisClient.analyzeAndPersist(entity, caseDocumentRepository.findByCaseId(caseId));
        return toResponse(entity);
    }

    /** Persists each uploaded document, replacing any prior document of the same type. */
    /**
     * "case" is a reserved key: the frontend sends the case JSON payload itself as a Blob under
     * that same multipart field name (see ExpedienteService.create), and Spring's {@code
     * Map<String, MultipartFile>} binding picks it up alongside the real documents. It's not a
     * document — filtering it out here keeps it out of case_documents (and out of what gets
     * forwarded to classification-service's OCR, which would otherwise try to read the JSON bytes
     * as an image and fail with "Failed to load image or audio file").
     */
    private static final String CASE_PAYLOAD_KEY = "case";

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

    /**
     * {@code insurerSlug} sólo hace falta para el asegurado que es cliente de más de una compañía:
     * los ids son autoincrementales por esquema, así que sin él "el expediente 4" es ambiguo y
     * siempre se resolvía contra el tenant del login, dejando el de la otra aseguradora
     * inalcanzable desde el portal.
     */
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
        return toResponse(entity, history, caseAnalysisRepository.findByCaseId(caseId), null, null,
                caseDocumentAnalysisRepository.findByCaseId(caseId), traceabilityOf(entity));
    }

    @Override
    public Page<CaseResponse> listCases(CaseStatus status, String claimCause, String policyNumber,
                                         String insuredId, LocalDate eventDateFrom, LocalDate eventDateTo,
                                         String q, RiskBand riskBand, Long analystId, boolean assignedToMe,
                                         boolean unassigned, boolean fraudAlert, boolean assigned,
                                         boolean dueSoon, Pageable pageable) {
        if (accessPolicy.currentUserIsInsured()) {
            // El asegurado ve los suyos de TODAS sus aseguradoras, no solo la del tenant activo.
            // Las lentes no le aplican: no tiene expedientes "asignados" ni bandeja de fraude.
            return toInsuredResponses(insuredCaseAggregator.findOwnCases(
                    status, claimCause, policyNumber, eventDateFrom, eventDateTo, q, riskBand, pageable));
        }

        // "Los míos" gana sobre el filtro explícito: si vienen los dos, el analista está mirando su
        // propia bandeja y el id de otro no tendría sentido.
        Long ownerId = analystId;
        if (assignedToMe) {
            ownerId = currentAnalystId().orElse(null);
            if (ownerId == null) {
                // Sin perfil de analista en este tenant (el caso del referente) no hay expediente
                // propio que mostrar. Vacío y no "sin filtro": lo segundo devolvería TODOS, que es
                // justo lo contrario de lo que se pidió.
                return Page.empty(pageable);
            }
        }

        Specification<Case> spec = withDueSoon(CaseSpecifications.withFilters(
                status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, q, riskBand,
                ownerId, unassigned, fraudAlert, assigned), dueSoon);
        return toResponses(caseRepository.findAll(spec, pageable));
    }

    /** Umbral del filtro "por vencer" = mismo borde que el semáforo (deadlinePriority ≠ NONE). */
    private Specification<Case> withDueSoon(Specification<Case> base, boolean dueSoon) {
        if (!dueSoon) {
            return base;
        }
        Specification<Case> due = CaseSpecifications.dueSoonBefore(
                LocalDate.now(clock).plusDays(DeadlinePriority.WATCH_DAYS));
        return base == null ? due : base.and(due);
    }

    @Override
    public LensSummaryResponse lensSummary(CaseStatus status, String claimCause, String policyNumber,
                                            String insuredId, LocalDate eventDateFrom, LocalDate eventDateTo,
                                            String q, RiskBand riskBand, Long analystId) {
        // "Míos" necesita saber quién es "yo"; para el referente no hay perfil de analista y queda 0.
        Long me = currentAnalystId().orElse(null);
        return new LensSummaryResponse(
                count(status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, q, riskBand,
                        analystId, false, false, false),
                me == null ? 0 : count(status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo,
                        q, riskBand, me, false, false, false),
                count(status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, q, riskBand,
                        analystId, false, false, true),
                count(status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, q, riskBand,
                        analystId, true, false, false),
                count(status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, q, riskBand,
                        analystId, false, true, false));
    }

    private long count(CaseStatus status, String claimCause, String policyNumber, String insuredId,
                       LocalDate eventDateFrom, LocalDate eventDateTo, String q, RiskBand riskBand,
                       Long analystId, boolean unassigned, boolean fraudAlert, boolean assigned) {
        Specification<Case> spec = CaseSpecifications.withFilters(
                status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, q, riskBand,
                analystId, unassigned, fraudAlert, assigned);
        // Sin ningún filtro la spec queda null, y count(null) explota — findAll(null, pageable) no.
        return spec == null ? caseRepository.count() : caseRepository.count(spec);
    }

    /**
     * El analista del request, resuelto por el email del token contra {@code claims_analyst} del
     * tenant activo. El id es local al esquema, así que no viaja al frontend: la bandeja pide
     * "los míos" y esto resuelve quién es "yo" del lado que sí puede saberlo.
     */
    private Optional<Long> currentAnalystId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Optional.empty();
        }
        return claimsAnalystRepository.findByEmail(authentication.getName()).map(ClaimsAnalyst::getId);
    }

    /**
     * Igual que {@link #toResponses(Page)} pero conservando de qué aseguradora salió cada
     * expediente, que es lo único que después permite volver a abrirlo: los ids se repiten entre
     * esquemas. No se joinea el análisis — el asegurado no ve clasificación ni riesgo, y las
     * tablas de análisis son por esquema (habría que volver a saltar de tenant por cada fila).
     */
    private Page<CaseResponse> toInsuredResponses(Page<InsuredCaseAggregator.InsuredCase> page) {
        return page.map(it -> toResponse(it.caseRecord(), null, CaseAnalysis.none(),
                it.insurerSlug(), it.insurerName(), List.of()));
    }

    /** Resuelve el análisis joineado de toda una página de una sola vez. */
    Page<CaseResponse> toResponses(Page<Case> page) {
        // Un solo query para toda la página: pedir el análisis caso por caso acá es el N+1 que
        // hace colapsar la bandeja.
        Map<Long, CaseAnalysis> analyses = caseAnalysisRepository.findByCaseIds(
                page.getContent().stream().map(Case::getId).toList());
        return page.map(entity -> toResponse(entity, null,
                analyses.getOrDefault(entity.getId(), CaseAnalysis.none())));
    }

    @Override
    public CaseResponse assignAnalyst(Long caseId, Long analystId) {
        Case entity = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));

        // Se busca en el esquema del tenant activo: un id de analista de OTRA aseguradora
        // sencillamente no está en esta tabla, así que el aislamiento no necesita un chequeo
        // aparte. Se resuelve antes de escribir para no dejar el expediente con un dueño que
        // nadie puede convertir en una persona.
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

        // Nada se resetea a propósito. La decisión anterior existió y su registro en
        // classification-service es inmutable; el score de riesgo y el antecedente de fraude son
        // hechos del siniestro, no del veredicto. Reabrir devuelve el expediente a una persona, no
        // lo deja como si nunca se hubiera resuelto.
        //
        // transition() hace de guarda: solo los tres terminales tienen salida a
        // PENDING_ANALYST_REVIEW, así que reabrir un expediente abierto termina en 409. Y el reset
        // del plazo del art. 56 sale de ahí mismo (resumeDeadlineIfInterrupted): el expediente
        // vuelve con 30 días nuevos, no con la fecha vencida que tenía cuando se cerró.
        caseStatusService.transition(entity, CaseStatus.PENDING_ANALYST_REVIEW,
                accessPolicy.currentAssignmentActor(), "expediente reabierto: " + reason);

        return loadCase(caseId);
    }

    private static String fullName(ClaimsAnalyst analyst) {
        return analyst.getName() + " " + analyst.getSurname();
    }

    // Estados terminales: un expediente en uno de estos ya no es "carga" (no requiere más trabajo).
    // Derivado de CaseStatusService.TERMINAL_STATUSES y no escrito a mano: esta lista se quedó sin
    // LAPSED cuando se sumó la caducidad, y el expediente caducado le contaba como carga activa al
    // analista para siempre. `sorted()` solo para que el orden del IN sea estable entre corridas.
    private static final List<String> FINAL_STATUS_NAMES = CaseStatusService.TERMINAL_STATUSES.stream()
            .map(Enum::name)
            .sorted()
            .toList();

    // Bandas que cuentan como "alerta de fraude" en la tarjeta de riesgo del inicio del analista.
    private static final List<RiskBand> HIGH_RISK_BANDS = List.of(RiskBand.HIGH, RiskBand.CRITICAL);

    @Override
    public AssignedCaseSummaryResponse assignedCaseSummary() {
        // Un rol sin perfil de analista en el tenant (el referente) no tiene expedientes propios:
        // resumen vacío en vez de error, igual que la lente "Míos" de la bandeja.
        Long analystId = currentAnalystId().orElse(null);
        if (analystId == null) {
            return new AssignedCaseSummaryResponse(0, Map.of(), 0);
        }

        Map<String, Long> byStatus = new HashMap<>();
        long total = 0;
        for (CaseRepository.StatusCount row : caseRepository.countByStatusForAnalyst(analystId)) {
            byStatus.put(row.getStatus(), row.getTotal());
            total += row.getTotal();
        }

        long highRisk = caseRepository.countByAnalystAndRiskBandIn(analystId, HIGH_RISK_BANDS);
        return new AssignedCaseSummaryResponse(total, byStatus, highRisk);
    }

    @Override
    public List<AnalystWorkloadResponse> analystWorkload() {
        // Un solo query agrupado trae los conteos de los analistas que tienen expedientes activos.
        Map<Long, Long> activeByAnalyst = new HashMap<>();
        for (CaseRepository.AnalystCaseCount row : caseRepository.countActiveByAnalyst(FINAL_STATUS_NAMES)) {
            activeByAnalyst.put(row.getAnalystId(), row.getTotal());
        }

        // Se listan TODOS los analistas del tenant (los que no aparecieron arriba van con cero): el
        // panel muestra al equipo completo, no solo a los que tienen trabajo encima.
        List<AnalystWorkloadResponse> workload = new ArrayList<>();
        for (ClaimsAnalyst analyst : claimsAnalystRepository.findAll()) {
            workload.add(new AnalystWorkloadResponse(
                    analyst.getId(), fullName(analyst), activeByAnalyst.getOrDefault(analyst.getId(), 0L)));
        }

        // Más cargados primero; a igualdad de carga, alfabético por nombre para un orden estable.
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
            // Los adjuntos son parte del expediente: si no podés leerlo, tampoco su documentación.
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
        // readableCase is the whole authorization: the analyst reaches these policies through a
        // case they can already read, never by asking for a DNI.
        Case entity = readableCase(caseId);
        return policyService.listByInsured(entity.getInsured().getDni());
    }

    private Case readableCase(Long caseId) {
        Case entity = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));
        accessPolicy.assertCanRead(entity);
        return entity;
    }

    @Override
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

        // analystId nunca sale del cliente: un id mandado por el front dejaría atribuirle la
        // decisión a cualquier analista con solo cambiar el body. Se resuelve acá contra
        // claims_analyst por el email del JWT — mismo mecanismo que ya usa CaseAccessPolicy.
        String callerEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        ClaimsAnalyst analyst = claimsAnalystRepository.findByEmail(callerEmail)
                .orElseThrow(() -> new AnalystProfileNotFoundException(callerEmail));

        // Decidir es lo que gana el que tiene el expediente asignado, no cualquier
        // ANALISTA_SINIESTROS del tenant — @PreAuthorize solo valida el rol, esto valida el
        // expediente puntual. Sin analista asignado no hay a quién dejarle decidir: fuerza el
        // orden asignar → decidir en vez de que una decisión funcione como asignación implícita.
        if (entity.getAnalyst() == null) {
            throw new CaseNotAssignedException(caseId);
        }
        if (!entity.getAnalyst().getId().equals(analyst.getId())) {
            throw new CaseAssignedToAnotherAnalystException(caseId);
        }

        // El contador vivo es de `cases`; el registro auditable se queda con su valor final, así
        // que se lo mandamos nosotros — el frontend no lo conoce, igual que con analystId.
        AnalystDecisionRequest audited = new AnalystDecisionRequest(
                analyst.getId(), request.decision(), request.justification(), entity.getClassificationAttempts());

        // The decision row is created there, so its id only exists after the call. Storing it
        // links the case to the model run the verdict was based on.
        entity.setClassificationId(claimsAnalysisClient.forwardAnalystDecision(caseId, audited));

        caseStatusService.transition(entity, targetStatus,
                StatusChangeActor.ANALYST, "decisión del analista: " + request.decision());
    }

    /** Recién creado o recién reencolado: todavía no hay clasificación que mostrar. */
    private CaseResponse toResponse(Case entity) {
        return toResponse(entity, null, CaseAnalysis.none());
    }

    /**
     * The single place the joins get flattened back into the shape the frontend already speaks.
     * {@code Case} stores FKs and only the columns the inbox filters by; {@code CaseResponse} did
     * not change, and neither did the SPA.
     */
    private CaseResponse toResponse(Case entity, List<StatusTransitionResponse> history,
                                     CaseAnalysis analysis) {
        return toResponse(entity, history, analysis, null, null, List.of(), Traceability.none());
    }

    /** Sólo el detalle trae los datos extraídos; ver el javadoc del campo en {@link CaseResponse}. */
    private CaseResponse toResponse(Case entity, List<StatusTransitionResponse> history,
                                     CaseAnalysis analysis,
                                     List<DocumentAnalysisSummary> documentAnalyses) {
        return toResponse(entity, history, analysis, null, null, documentAnalyses, Traceability.none());
    }

    private CaseResponse toResponse(Case entity, List<StatusTransitionResponse> history,
                                     CaseAnalysis analysis, String insurerSlug, String insurerName,
                                     List<DocumentAnalysisSummary> documentAnalyses) {
        return toResponse(entity, history, analysis, insurerSlug, insurerName, documentAnalyses,
                Traceability.none());
    }

    private CaseResponse toResponse(Case entity, List<StatusTransitionResponse> history,
                                     CaseAnalysis analysis, String insurerSlug, String insurerName,
                                     List<DocumentAnalysisSummary> documentAnalyses,
                                     Traceability traceability) {
        // Mientras el expediente está de vuelta en clasificación, la corrida anterior sigue siendo
        // la última fila de llm_analysis. Mostrarla diría que hay una recomendación vigente cuando
        // justamente se está recalculando, así que en ese estado no se surface ninguna.
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
                history,
                documentAnalyses,
                traceability.ruleResults(),
                traceability.policySnapshot()
        );
    }

    /**
     * Nothing to flag as due: either the case is closed, or the term is currently interrupted and
     * {@code responseDeadline} is a frozen, stale date until it resumes. Both readings live in
     * {@code CaseStatusService.isDeadlineRunning} — this is its negation, and enumerating the
     * statuses here again is what let {@code LAPSED} drift into three different lists.
     */
    private boolean isDeadlineInactive(Case entity) {
        return !CaseStatusService.isDeadlineRunning(entity.getStatus());
    }

    /**
     * What the analysis tab reads, all of it persisted at classification time. Grouped so the
     * mapper doesn't grow two more parameters that every list-endpoint caller has to pass empty.
     *
     * @param ruleResults null when they couldn't be read — never null for "none ran", which is
     *                    the empty list. See {@code CaseResponse.ruleResults}.
     */
    private record Traceability(List<RuleResultResponse> ruleResults,
                                PolicySnapshotResponse policySnapshot) {

        static Traceability none() {
            return new Traceability(List.of(), null);
        }
    }

    /**
     * Empty for the insured: this is the analyst's audit trail, and the portal is not the place to
     * widen what it already shows. The fields that reached them before it ({@code riskBreakdown},
     * {@code forensicReport}, {@code documentAnalyses}) are left as they were.
     */
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
     * Un Fast Track no deja fila en {@code llm_analysis} (el modelo nunca corrió, y el CHECK de la
     * tabla rechaza {@code FAST_TRACK}), así que ahí la clasificación sale de {@code was_fast_track}.
     * Mismo criterio que {@code ClassificationResultsService.getStatus}.
     */
    /**
     * El Fast Track se pregunta primero, no último: no deja fila en {@code llm_analysis}, y como
     * esa tabla es append-only, preguntar por ella antes hace ganar a la corrida ANTERIOR. Se ve
     * al reclasificar — subir la documentación faltante y que el gate resuelva Fast Track dejaba
     * el {@code FALTA_DOCUMENTACION} viejo a la vista. El flag se reescribe en cada corrida, así
     * que en true siempre significa "la última fue Fast Track".
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

    /**
     * Uno por fila, tal como está en {@code llm_reason} — no se aplana a un string acá; ver el
     * javadoc de {@link CaseResponse#analysisReasons()}.
     */
    private List<String> reasonsOf(Case entity, CaseAnalysis analysis) {
        // Mismo criterio que classificationOf: los motivos de llm_reason son de la corrida
        // anterior, y atribuírselos a un Fast Track sería darle razones que no son suyas.
        if (wasFastTracked(entity)) {
            return List.of();
        }
        return analysis.factors();
    }

    private boolean wasFastTracked(Case entity) {
        return Boolean.TRUE.equals(entity.getDeterministicFastTrack());
    }
}
