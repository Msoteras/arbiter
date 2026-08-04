package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseDocumentResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.dto.StatusTransitionResponse;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import ar.edu.utn.frba.arbiter.cases.exceptions.AnalystProfileNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.DocumentNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.DocumentReadException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidAnalystDecisionException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidStatusTransitionException;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseAnalysisRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseAnalysisRepository.CaseAnalysis;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseSpecifications;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CaseServiceImpl implements CaseService {

    private final CaseRepository caseRepository;
    private final CaseDocumentRepository caseDocumentRepository;
    private final CaseStatusService caseStatusService;
    private final ClaimsAnalysisClient claimsAnalysisClient;
    private final CaseReferenceResolver referenceResolver;
    private final CaseAnalysisRepository caseAnalysisRepository;
    private final CaseAccessPolicy accessPolicy;
    private final InsuredCaseAggregator insuredCaseAggregator;
    private final PolicyTenantLocator policyTenantLocator;
    private final InsurerRepository insurerRepository;
    private final ClaimsAnalystRepository claimsAnalystRepository;

    /**
     * Ley 17.418 art. 56: the insurer has 30 days from the denuncia to pronounce itself, and
     * staying silent means acceptance. A constant and not a rule in rules-service because it
     * isn't the insurer's to configure — the law sets it, the same for every tenant.
     */
    private static final int RESPONSE_TERM_DAYS = 30;

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
        // Every string in the request has to name something the tenant actually has; anything
        // that doesn't resolve fails with 422 rather than being stored as free text.
        Policy policy = referenceResolver.resolvePolicy(request.policyNumber());
        Insured insured = referenceResolver.applyDeclaredDetails(
                referenceResolver.resolveInsured(request.insuredId()), request);

        Case entity = Case.builder()
                .claimCause(referenceResolver.resolveClaimCause(request.branch(), request.claimCause()))
                .declaredItem(request.insuredItem())
                .insured(insured)
                .policy(policy)
                .coverage(policy.getCoverage())
                .description(request.description())
                .occurredAt(request.eventDate())
                .policeReportAt(request.policeReportAt())
                .eventAddress(request.eventLocation())
                .claimedAmount(request.claimedAmount())
                // Desde la denuncia, que es este mismo momento: `reportedAt` lo pone Hibernate
                // recién al insertar, así que acá todavía es null.
                .responseDeadline(LocalDate.now().plusDays(RESPONSE_TERM_DAYS))
                .currentStatus(caseStatusService.initialStatus())
                .build();

        Case saved = caseRepository.save(entity);
        caseStatusService.recordCreation(saved, StatusChangeActor.INSURED, "denuncia registrada");
        storeDocuments(saved.getId(), documents);
        claimsAnalysisClient.analyzeAndPersist(saved, caseDocumentRepository.findByCaseId(saved.getId()));

        return toResponse(saved);
    }

    @Override
    public CaseResponse addDocumentsAndReclassify(Long caseId, Map<String, MultipartFile> documents) {
        Case entity = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));

        storeDocuments(caseId, documents);

        // Clear the cached risk so the recalculation window reads as "sin scorear"/recalculando,
        // never a stale band. It's re-populated by the classification poll once the new score lands.
        // The model's recommendation needs no reset: llm_analysis is append-only, and while the
        // case is back in PENDING_CLASSIFICATION toResponse doesn't surface the previous run.
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
        if (insurerSlug == null || insurerSlug.isBlank()) {
            return loadCase(caseId);
        }
        // El slug viene del pedido, así que sólo se busca entre las aseguradoras del claim
        // firmado: es lo que impide nombrar la de otra compañía. Si no está, 404 y no 403 —
        // mismo criterio que un expediente ajeno, para no confirmar qué tenants existen.
        Insurer insurer = insurerRepository.findAllById(CallerContext.get().insurerIds()).stream()
                .filter(Insurer::isActive)
                .filter(candidate -> InsurerSlug.matches(candidate, insurerSlug))
                .findFirst()
                .orElseThrow(() -> new CaseNotFoundException(caseId));

        String callerTenant = TenantContext.get();
        try {
            TenantContext.set(insurer.getSchemaName());
            return loadCase(caseId);
        } finally {
            // Igual que en el agregador: si no se restaura, la conexión vuelve al pool viendo el
            // esquema equivocado y se lo lleva puesto el próximo request.
            TenantContext.set(callerTenant);
        }
    }

    private CaseResponse loadCase(Long caseId) {
        Case entity = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));
        accessPolicy.assertCanRead(entity);
        List<StatusTransitionResponse> history = caseStatusService.history(caseId).stream()
                .map(StatusTransitionResponse::from)
                .toList();
        return toResponse(entity, history, caseAnalysisRepository.findByCaseId(caseId));
    }

    @Override
    public Page<CaseResponse> listCases(CaseStatus status, String claimCause, String policyNumber,
                                         String insuredId, LocalDate eventDateFrom, LocalDate eventDateTo,
                                         String q, RiskBand riskBand, Pageable pageable) {
        if (accessPolicy.currentUserIsInsured()) {
            // El asegurado ve los suyos de TODAS sus aseguradoras, no solo la del tenant activo.
            return toInsuredResponses(insuredCaseAggregator.findOwnCases(
                    status, claimCause, policyNumber, eventDateFrom, eventDateTo, q, riskBand, pageable));
        }
        Specification<Case> spec = CaseSpecifications.withFilters(
                status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, q, riskBand);
        return toResponses(caseRepository.findAll(spec, pageable));
    }

    /**
     * Igual que {@link #toResponses(Page)} pero conservando de qué aseguradora salió cada
     * expediente, que es lo único que después permite volver a abrirlo: los ids se repiten entre
     * esquemas. No se joinea el análisis — el asegurado no ve clasificación ni riesgo, y las
     * tablas de análisis son por esquema (habría que volver a saltar de tenant por cada fila).
     */
    private Page<CaseResponse> toInsuredResponses(Page<InsuredCaseAggregator.InsuredCase> page) {
        return page.map(it -> toResponse(it.caseRecord(), null, CaseAnalysis.none(),
                it.insurerSlug(), it.insurerName()));
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
    public List<CaseDocumentResponse> getDocuments(Long caseId) {
        // Los adjuntos son parte del expediente: si no podés leerlo, tampoco su documentación.
        readableCase(caseId);
        return caseDocumentRepository.findByCaseId(caseId).stream()
                .map(CaseDocumentResponse::from)
                .toList();
    }

    @Override
    public CaseDocument getDocument(Long caseId, Long documentId) {
        readableCase(caseId);
        return caseDocumentRepository.findById(documentId)
                .filter(doc -> doc.getCaseId().equals(caseId))
                .orElseThrow(() -> new DocumentNotFoundException(caseId, documentId));
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
        return toResponse(entity, history, analysis, null, null);
    }

    private CaseResponse toResponse(Case entity, List<StatusTransitionResponse> history,
                                     CaseAnalysis analysis, String insurerSlug, String insurerName) {
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
                entity.getDeclaredItem(),
                entity.getInsured().getDni(),
                entity.getInsured().fullName(),
                entity.getPolicy().getExternalPolicyNumber(),
                entity.getDescription(),
                entity.getOccurredAt(),
                entity.getEventAddress(),
                entity.getClaimedAmount(),
                classificationOf(entity, current),
                confidenceOf(entity, current),
                detailOf(entity, current),
                entity.getRiskScore(),
                entity.getRiskBand(),
                current.riskBreakdown(),
                entity.getForensicReport(),
                entity.getReportedAt(),
                entity.getUpdatedAt(),
                history
        );
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

    private String detailOf(Case entity, CaseAnalysis analysis) {
        // Mismo criterio que classificationOf: los motivos de llm_reason son de la corrida
        // anterior, y atribuírselos a un Fast Track sería darle razones que no son suyas.
        if (wasFastTracked(entity)) {
            return "Fast track classification available";
        }
        if (!analysis.factors().isEmpty()) {
            return String.join(", ", analysis.factors());
        }
        return analysis.classification() == null ? null : "Classification completed";
    }

    private boolean wasFastTracked(Case entity) {
        return Boolean.TRUE.equals(entity.getDeterministicFastTrack());
    }
}
