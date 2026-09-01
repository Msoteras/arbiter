package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.dto.EligibilityCheckRequest;
import ar.edu.utn.frba.arbiter.cases.dto.EligibilityCheckResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.AnalystNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.AnalystProfileNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseAssignedToAnotherAnalystException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotAssignedException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InsuredIdentityMismatchException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidAnalystDecisionException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidStatusTransitionException;
import ar.edu.utn.frba.arbiter.cases.exceptions.PolicyInsuredMismatchException;
import ar.edu.utn.frba.arbiter.cases.exceptions.PolicyNotEligibleException;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseStatusHistory;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseAnalysisRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseAnalysisRepository.CaseAnalysis;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentAnalysisRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.cases.support.CaseStates;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CaseServiceImplTest {

    /** El DNI que trae {@link #caseRequest()} y, por defecto, también el token del caller. */
    private static final String CALLER_DNI = "40.123.456";

    @Mock
    private CaseRepository caseRepository;

    @Mock
    private CaseDocumentRepository caseDocumentRepository;

    @Mock
    private CaseStatusService caseStatusService;

    @Mock
    private ClaimsAnalysisClient claimsAnalysisClient;

    @Mock
    private ClaimsAnalystRepository claimsAnalystRepository;

    @Mock
    private CaseReferenceResolver referenceResolver;

    @Mock
    private CaseAnalysisRepository caseAnalysisRepository;

    @Mock
    private CaseDocumentAnalysisRepository caseDocumentAnalysisRepository;

    @Mock
    private CaseAccessPolicy accessPolicy;

    @Mock
    private InsuredCaseAggregator insuredCaseAggregator;

    @Mock
    private PolicyTenantLocator policyTenantLocator;

    @Mock
    private PolicyEligibilityValidator policyEligibilityValidator;

    @Mock
    private InsurerRepository insurerRepository;

    @Mock
    private Clock clock;

    @Mock
    private PolicyCoverageResolver policyCoverageResolver;

    @InjectMocks
    private CaseServiceImpl caseService;

    /**
     * Por defecto no hay clasificación joineada. Es lo que devuelve el repositorio para un
     * expediente recién creado, y deja que cada test que sí la necesita la sobrescriba.
     */
    /**
     * Por defecto la póliza tiene una sola cobertura contratada y es la que responde por cualquier
     * hecho. Los tests que necesitan varias la sobrescriben — acá lo que se prueba es el alta, no
     * la resolución de cobertura (eso vive en {@link PolicyCoverageResolverTest}).
     */
    @BeforeEach
    void oneContractedCoverageByDefault() {
        lenient().when(policyCoverageResolver.resolveFor(any(), any()))
                .thenReturn(CaseFixtures.policyCoverage(1L, CaseFixtures.coverage("Celulares"), 1));
    }

    @BeforeEach
    void noAnalysisByDefault() {
        // toResponse() computa la prioridad de vencimiento con LocalDate.now(clock).
        lenient().when(clock.instant()).thenReturn(Instant.parse("2026-06-15T12:00:00Z"));
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        lenient().when(caseAnalysisRepository.findByCaseId(any())).thenReturn(CaseAnalysis.none());
        lenient().when(caseAnalysisRepository.findByCaseIds(any())).thenReturn(Map.of());
        // Sin extracciones por default: el mock devolvería null, y el real devuelve lista vacía
        // cuando el expediente no se clasificó todavía — que es el caso de casi todos los tests.
        lenient().when(caseDocumentAnalysisRepository.findByCaseId(any())).thenReturn(List.of());
    }

    /**
     * createCase() ahora resuelve el insurerSlug/insurerName de la respuesta contra el tenant
     * actual (para que el frontend pueda reenviarlo en llamadas posteriores sobre el expediente,
     * si terminó en una aseguradora distinta de la del login). Sin aseguradora encontrada, la
     * respuesta simplemente queda con esos dos campos en null — el mismo comportamiento de antes.
     */
    @BeforeEach
    void noInsurerMatchByDefault() {
        lenient().when(insurerRepository.findBySchemaName(any())).thenReturn(Optional.empty());
    }

    /**
     * El alta ahora exige que el DNI del payload sea el del token (D2), así que todo test de
     * createCase necesita un caller con el mismo DNI que usa {@link #caseRequest()}. Los tests que
     * prueban el rechazo lo pisan.
     */
    @BeforeEach
    void callerIsTheInsuredFiling() {
        CallerContext.set(new CallerContext.Caller(CALLER_DNI, List.of(1L), "arbiter_bbva"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        CallerContext.clear();
    }

    /** recordAnalystDecision resuelve el analista contra este email, no contra el request. */
    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    @Test
    void createCase_persistsEntityAndTriggersClassification() {
        CaseRequest request = caseRequest();
        Case saved = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        stubReferenceResolution();
        when(caseStatusService.initialStatus()).thenReturn(CaseStates.of(CaseStatus.PENDING_CLASSIFICATION));
        when(caseRepository.save(any(Case.class))).thenReturn(saved);
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        CaseResponse response = caseService.createCase(request, null);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo(CaseStatus.PENDING_CLASSIFICATION);
        assertThat(response.branch()).isEqualTo("Celulares");

        ArgumentCaptor<Case> captor = ArgumentCaptor.forClass(Case.class);
        verify(caseRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CaseStatus.PENDING_CLASSIFICATION);

        // Ubicación normalizada en sus tres columnas: antes el wizard concatenaba todo en
        // eventAddress y province/locality quedaban en null, dejando la zona del hecho como prosa
        // no consultable.
        assertThat(captor.getValue().getEventAddress()).isEqualTo("Av. Rivadavia 1234");
        assertThat(captor.getValue().getProvince()).isEqualTo("Buenos Aires");
        assertThat(captor.getValue().getLocality()).isEqualTo("CABA");

        verify(claimsAnalysisClient).analyzeAndPersist(eq(saved), eq(List.of()));
    }

    @Test
    void createCase_blankProvinceAndLocalityBecomeNull() {
        // Campos opcionales: si el asegurado no los carga, se guarda null y no una cadena vacía.
        CaseRequest request = new CaseRequest(
                "Celulares", "Celular Protegido Básico", "Robo en vía pública",
                "Motorola Edge 50 Pro", CALLER_DNI, "POL-CEL-2024-001",
                "Me robaron el celular", LocalDateTime.of(2026, 6, 13, 19, 45),
                "Av. Rivadavia 1234", "   ", "",
                null, new BigDecimal("150000"), null, null, null, null);
        stubReferenceResolution();
        when(caseStatusService.initialStatus()).thenReturn(CaseStates.of(CaseStatus.PENDING_CLASSIFICATION));
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> {
            Case e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        caseService.createCase(request, null);

        ArgumentCaptor<Case> captor = ArgumentCaptor.forClass(Case.class);
        verify(caseRepository).save(captor.capture());
        assertThat(captor.getValue().getProvince()).isNull();
        assertThat(captor.getValue().getLocality()).isNull();
    }

    @Test
    void createCase_mapsAllFieldsFromRequest() {
        CaseRequest request = caseRequest();
        stubReferenceResolution();
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> {
            Case e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        CaseResponse response = caseService.createCase(request, null);

        assertThat(response.branch()).isEqualTo(request.branch());
        assertThat(response.product()).isEqualTo(request.product());
        assertThat(response.claimCause()).isEqualTo(request.claimCause());
        assertThat(response.insuredItem()).isEqualTo(request.insuredItem());
        assertThat(response.insuredId()).isEqualTo(request.insuredId());
        assertThat(response.policyNumber()).isEqualTo(request.policyNumber());
        assertThat(response.description()).isEqualTo(request.description());
        assertThat(response.eventDate()).isEqualTo(request.eventDate());
        assertThat(response.eventLocation()).isEqualTo(request.eventLocation());
        assertThat(response.claimedAmount()).isEqualByComparingTo(request.claimedAmount());
    }

    @Test
    void createCase_routesDeclaredDetailsToTheInsured() {
        // contacto describe a la persona, no al siniestro: desde el DER vive en `insured`.
        // pep e imageConsent ya no se tocan acá — vienen del onboarding/perfil.
        CaseRequest request = new CaseRequest(
                "Celulares", "Celular Protegido Básico", "Robo en vía pública",
                "Motorola Edge 50 Pro", "40.123.456", "POL-CEL-2024-001",
                "Me robaron el celular en la estación de subte",
                LocalDateTime.of(2026, 6, 13, 19, 45), "Av. Rivadavia 1234",
                "Buenos Aires", // province
                "CABA", // locality
                null, // policeReportAt
                new BigDecimal("150000"),
                null, null,
                "test@example.com", "11-5555-0000"
        );
        Insured insured = CaseFixtures.insured("40.123.456", "Laura", "Fernández");
        stubReferenceResolution();
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> {
            Case e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        caseService.createCase(request, null);

        verify(referenceResolver).applyDeclaredDetails(any(Insured.class), eq(request));
    }

    @Test
    void createCase_unresolvablePolicy_throwsAndPersistsNothing() {
        CaseRequest request = caseRequest();
        // El asegurado se resuelve antes que la póliza (la sincronización a demanda necesita a quién
        // apuntar como titular), así que este test también lo necesita aunque no lo ejercite.
        Insured insured = CaseFixtures.insured(CALLER_DNI, "Laura", "Fernández");
        insured.setId(1L);
        when(referenceResolver.resolveInsured(CALLER_DNI)).thenReturn(insured);
        when(referenceResolver.applyDeclaredDetails(insured, request)).thenReturn(insured);
        when(referenceResolver.resolvePolicy(eq("POL-CEL-2024-001"), eq(1L)))
                .thenThrow(new UnresolvedCaseReferenceException("policy", "POL-CEL-2024-001"));

        assertThatThrownBy(() -> caseService.createCase(request, null))
                .isInstanceOf(UnresolvedCaseReferenceException.class)
                .hasMessageContaining("POL-CEL-2024-001");

        verify(caseRepository, never()).save(any());
        verify(claimsAnalysisClient, never()).analyzeAndPersist(any(), any());
    }

    // ─────────── D2: no se denuncia a nombre de otro ───────────

    @Test
    void createCase_denunciaOnBehalfOfAnotherInsured_isRejected() {
        // El payload nombra un DNI distinto al del token. Antes se resolvía igual: insuredId y
        // policyNumber eran dos búsquedas sueltas que nadie cruzaba contra el usuario.
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L), "arbiter_bbva"));

        assertThatThrownBy(() -> caseService.createCase(caseRequest(), null))
                .isInstanceOf(InsuredIdentityMismatchException.class);

        // Falla antes de resolver nada: no se toca la base ni se encola una clasificación.
        verify(referenceResolver, never()).resolvePolicy(any(), any());
        verify(caseRepository, never()).save(any());
        verify(claimsAnalysisClient, never()).analyzeAndPersist(any(), any());
    }

    @Test
    void createCase_callerWithoutDni_isRejected() {
        // Analista o referente: no tienen fila en `insured`, así que el token no trae DNI. El rol ya
        // los bloquea en el controller; acá se verifica que el servicio no los deje pasar por null.
        CallerContext.set(new CallerContext.Caller(null, List.of(1L), "arbiter_bbva"));

        assertThatThrownBy(() -> caseService.createCase(caseRequest(), null))
                .isInstanceOf(InsuredIdentityMismatchException.class);

        verify(caseRepository, never()).save(any());
    }

    @Test
    void createCase_policyBelongingToAnotherInsured_isRejected() {
        // Los dos resuelven bien por separado; lo que no existe es la combinación. Con ids
        // explícitos porque es justamente la pareja lo que se compara.
        Insured insured = CaseFixtures.insured(CALLER_DNI, "Laura", "Fernández");
        insured.setId(7L);
        Policy someoneElsesPolicy = CaseFixtures.policy("POL-CEL-2024-001", "Celular Protegido Básico");
        someoneElsesPolicy.setInsuredId(99L);

        when(referenceResolver.resolvePolicy(any(), any())).thenReturn(someoneElsesPolicy);
        when(referenceResolver.resolveInsured(any())).thenReturn(insured);
        when(referenceResolver.applyDeclaredDetails(any(), any())).thenReturn(insured);

        assertThatThrownBy(() -> caseService.createCase(caseRequest(), null))
                .isInstanceOf(PolicyInsuredMismatchException.class)
                .hasMessageContaining("POL-CEL-2024-001");

        verify(caseRepository, never()).save(any());
        verify(claimsAnalysisClient, never()).analyzeAndPersist(any(), any());
    }

    @Test
    void createCase_policyOfTheInsuredFiling_goesThrough() {
        // La contracara del anterior: misma pareja, ids que coinciden, el alta procede.
        Insured insured = CaseFixtures.insured(CALLER_DNI, "Laura", "Fernández");
        insured.setId(7L);
        Policy ownPolicy = CaseFixtures.policy("POL-CEL-2024-001", "Celular Protegido Básico");
        ownPolicy.setInsuredId(7L);

        when(referenceResolver.resolvePolicy(any(), any())).thenReturn(ownPolicy);
        when(referenceResolver.resolveInsured(any())).thenReturn(insured);
        when(referenceResolver.applyDeclaredDetails(any(), any())).thenReturn(insured);
        when(referenceResolver.resolveClaimCause(any(), any()))
                .thenReturn(CaseFixtures.claimCause("Celulares", "Robo en vía pública"));
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> {
            Case e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        assertThat(caseService.createCase(caseRequest(), null).id()).isEqualTo(1L);

        verify(caseRepository).save(any(Case.class));
    }

    @Test
    void getCase_joinsTheClassificationFromLlmAnalysis() {
        // La recomendación ya no es columna de `cases`: sale del join, y los motivos viajan uno
        // por fila (llm_reason), no aplanados a un string — respeta el DER.
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(caseAnalysisRepository.findByCaseId(1L)).thenReturn(new CaseAnalysis(
                Classification.LLM_RECOMIENDA_APROBAR, 0.87,
                List.of("Monto bajo", "Primer siniestro"), null));

        CaseResponse response = caseService.getCase(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo(CaseStatus.PENDING_ANALYST_REVIEW);
        assertThat(response.analysisClassification()).isEqualTo(Classification.LLM_RECOMIENDA_APROBAR);
        assertThat(response.analysisConfidence()).isEqualTo(0.87);
        assertThat(response.analysisReasons()).containsExactly("Monto bajo", "Primer siniestro");
    }

    @Test
    void getCase_fastTracked_reportsFastTrackWithoutAnLlmRow() {
        // Un Fast Track no deja fila en llm_analysis (el CHECK de la tabla rechaza FAST_TRACK),
        // así que la clasificación tiene que salir de was_fast_track o se vería "sin clasificar".
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setDeterministicFastTrack(true);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        CaseResponse response = caseService.getCase(1L);

        assertThat(response.analysisClassification()).isEqualTo(Classification.FAST_TRACK);
        assertThat(response.analysisConfidence()).isEqualTo(1.0);
        // Sin motivos propios: los de llm_reason (si los hay) son de la corrida anterior, y
        // atribuírselos al Fast Track le daría razones que no son suyas.
        assertThat(response.analysisReasons()).isEmpty();
    }

    @Test
    void getCase_beingReclassified_doesNotSurfaceThePreviousRun() {
        // llm_analysis es append-only: mientras se recalcula, la corrida vieja sigue siendo la
        // última fila. Mostrarla diría que hay recomendación vigente justo cuando no la hay.
        Case entity = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(caseAnalysisRepository.findByCaseId(1L)).thenReturn(new CaseAnalysis(
                Classification.LLM_RECOMIENDA_APROBAR, 0.9, List.of("Motivo viejo"), null));

        CaseResponse response = caseService.getCase(1L);

        assertThat(response.analysisClassification()).isNull();
        assertThat(response.analysisConfidence()).isEqualTo(0.0);
        assertThat(response.analysisReasons()).isEmpty();
    }

    @Test
    void getCase_includesCachedForensicReport() {
        ImageForensicReport.InternalMatch match =
                new ImageForensicReport.InternalMatch(4L, "item_photo", "IMG_2831.jpg", 0.97);
        ImageForensicReport.ImageFinding finding =
                new ImageForensicReport.ImageFinding("item_photo-0", "item_photo", List.of(match), null);
        ImageForensicReport report = new ImageForensicReport(1, 0, true, List.of(finding));

        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setForensicReport(report);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        CaseResponse response = caseService.getCase(1L);

        assertThat(response.forensicReport()).isNotNull();
        assertThat(response.forensicReport().imagesAnalyzed()).isEqualTo(1);
        assertThat(response.forensicReport().findings()).hasSize(1);
        assertThat(response.forensicReport().findings().get(0).internalMatches())
                .containsExactly(match);
    }

    @Test
    void getCase_notFound_throwsCaseNotFoundException() {
        when(caseRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> caseService.getCase(999L))
                .isInstanceOf(CaseNotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    void addDocumentsAndReclassify_resetsClassificationAndRetriggers() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        // transition drives the status change; mirror it so the response reflects the new state
        when(caseStatusService.transition(any(), eq(CaseStatus.PENDING_CLASSIFICATION), any(), any()))
                .thenAnswer(inv -> {
                    Case e = inv.getArgument(0);
                    e.setCurrentStatus(CaseStates.of(CaseStatus.PENDING_CLASSIFICATION));
                    return e;
                });
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        CaseResponse response = caseService.addDocumentsAndReclassify(1L, Map.of());

        assertThat(response.status()).isEqualTo(CaseStatus.PENDING_CLASSIFICATION);
        assertThat(response.analysisClassification()).isNull();
        assertThat(response.analysisConfidence()).isEqualTo(0.0);
        assertThat(response.analysisReasons()).isEmpty();

        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.PENDING_CLASSIFICATION),
                eq(StatusChangeActor.INSURED), any());
        verify(claimsAnalysisClient).analyzeAndPersist(eq(entity), eq(List.of()));
    }

    @Test
    void addDocumentsAndReclassify_notFound_throwsCaseNotFoundException() {
        when(caseRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> caseService.addDocumentsAndReclassify(999L, Map.of()))
                .isInstanceOf(CaseNotFoundException.class);
    }

    @Test
    void addDocumentsAndReclassify_someoneElsesCase_isRejected() {
        // D1: era un findById pelado, así que cualquier asegurado podía subir documentación al
        // expediente de otro y forzarle una reclasificación. Ahora pasa por el mismo control de
        // pertenencia que las lecturas — y falla como 404, no 403, para no confirmar que existe.
        Case someoneElses = caseRecord(1L, CaseStatus.AWAITING_DOCUMENTATION);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(someoneElses));
        doThrow(new CaseNotFoundException(1L)).when(accessPolicy).assertCanRead(someoneElses);

        assertThatThrownBy(() -> caseService.addDocumentsAndReclassify(1L, Map.of()))
                .isInstanceOf(CaseNotFoundException.class);

        // Nada se guardó ni se reencoló: el rechazo es antes de tocar el expediente.
        verify(caseDocumentRepository, never()).save(any());
        verify(claimsAnalysisClient, never()).analyzeAndPersist(any(), any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void listCases_noFilter_returnsAllOrderedMostRecentFirst() {
        Case case2 = caseRecord(2L, CaseStatus.PENDING_ANALYST_REVIEW);
        Case case1 = caseRecord(1L, CaseStatus.APPROVED);
        Pageable pageable = PageRequest.of(0, 20);
        when(caseRepository.findAll(isNull(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(case2, case1), pageable, 2));

        Page<CaseResponse> response = caseService.listCases(null, null, null, null, null, null, null, null, false, pageable);

        assertThat(response.getContent()).hasSize(2);
        assertThat(response.getContent().get(0).id()).isEqualTo(2L);
        assertThat(response.getContent().get(1).id()).isEqualTo(1L);
    }

    @Test
    void listCases_withStatusFilter_appliesStatusSpec() {
        Case entity = caseRecord(3L, CaseStatus.PENDING_ANALYST_REVIEW);
        Pageable pageable = PageRequest.of(0, 20);
        when(caseRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));

        Page<CaseResponse> response = caseService.listCases(
                CaseStatus.PENDING_ANALYST_REVIEW, null, null, null, null, null, null, null, false, pageable);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).status()).isEqualTo(CaseStatus.PENDING_ANALYST_REVIEW);
    }

    @Test
    void listCases_withInsuredIdFilter_returnsOnlyThatInsuredsCases() {
        Case entity = caseRecord(4L, CaseStatus.PENDING_CLASSIFICATION);
        Pageable pageable = PageRequest.of(0, 20);
        when(caseRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));

        Page<CaseResponse> response = caseService.listCases(
                null, null, null, "40.123.456", null, null, null, null, false, pageable);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).insuredId()).isEqualTo("40.123.456");
    }

    @Test
    void listCases_withFreeTextSearch_passesQToSpecifications() {
        Case entity = caseRecord(6L, CaseStatus.PENDING_ANALYST_REVIEW);
        Pageable pageable = PageRequest.of(0, 20);
        when(caseRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));

        Page<CaseResponse> response = caseService.listCases(
                null, null, null, null, null, null, "POL-CEL", null, false, pageable);

        assertThat(response.getContent()).hasSize(1);
        verify(caseRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void listCases_withRiskBandFilter_passesRiskBandToSpecifications() {
        Case entity = caseRecord(7L, CaseStatus.PENDING_ANALYST_REVIEW);
        Pageable pageable = PageRequest.of(0, 20);
        when(caseRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));

        Page<CaseResponse> response = caseService.listCases(
                null, null, null, null, null, null, null, RiskBand.HIGH, false, pageable);

        assertThat(response.getContent()).hasSize(1);
        verify(caseRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void listCases_withClaimCausePolicyNumberAndDateRange_combinesFilters() {
        Case entity = caseRecord(5L, CaseStatus.APPROVED);
        Pageable pageable = PageRequest.of(0, 20);
        when(caseRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));

        Page<CaseResponse> response = caseService.listCases(
                CaseStatus.APPROVED, "Robo en vía pública", "POL-CEL-2024-001", "40.123.456",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), null, null, false, pageable);

        assertThat(response.getContent()).hasSize(1);
        verify(caseRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void listCases_noResults_returnsEmptyPage() {
        Pageable pageable = PageRequest.of(0, 20);
        when(caseRepository.findAll(isNull(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        Page<CaseResponse> response = caseService.listCases(null, null, null, null, null, null, null, null, false, pageable);

        assertThat(response.getContent()).isEmpty();
        assertThat(response.getTotalElements()).isZero();
    }

    @Test
    void assignAnalyst_setsOwnerResolvedFromTheTenant() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(claimsAnalystRepository.findById(7L)).thenReturn(Optional.of(analyst(7L, "Lucas", "Gómez")));

        CaseResponse response = caseService.assignAnalyst(1L, 7L);

        assertThat(response.assignedAnalystId()).isEqualTo(7L);
        assertThat(response.assignedAnalystName()).isEqualTo("Lucas Gómez");
        verify(caseRepository).save(entity);
    }

    @Test
    void assignAnalyst_doesNotChangeCaseStatus() {
        // Asignar es poner dueño, no resolver: el expediente sigue esperando la decisión del
        // analista (human-in-the-loop, decisión de arquitectura #5).
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(claimsAnalystRepository.findById(7L)).thenReturn(Optional.of(analyst(7L, "Lucas", "Gómez")));

        caseService.assignAnalyst(1L, 7L);

        assertThat(entity.getStatus()).isEqualTo(CaseStatus.PENDING_ANALYST_REVIEW);
        verify(caseStatusService, never()).transition(any(), any(), any(), anyString());
    }

    @Test
    void assignAnalyst_recordsAssignmentInHistory() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(claimsAnalystRepository.findById(7L)).thenReturn(Optional.of(analyst(7L, "Lucas", "Gómez")));
        when(accessPolicy.currentAssignmentActor()).thenReturn(StatusChangeActor.ANALYST);

        caseService.assignAnalyst(1L, 7L);

        verify(caseStatusService).recordAssignment(entity, StatusChangeActor.ANALYST,
                "expediente asignado a Lucas Gómez");
    }

    @Test
    void assignAnalyst_calledByReferent_attributesTheHistoryRowToTheReferent() {
        // El endpoint es compartido entre ANALISTA_SINIESTROS y REFERENTE_ASEGURADORA — el
        // historial no puede atribuirle todo al analista solo porque es el caso más común.
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(claimsAnalystRepository.findById(7L)).thenReturn(Optional.of(analyst(7L, "Lucas", "Gómez")));
        when(accessPolicy.currentAssignmentActor()).thenReturn(StatusChangeActor.REFERENT);

        caseService.assignAnalyst(1L, 7L);

        verify(caseStatusService).recordAssignment(entity, StatusChangeActor.REFERENT,
                "expediente asignado a Lucas Gómez");
    }

    @Test
    void assignAnalyst_reassigning_replacesThePreviousOwner() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setAnalyst(analyst(7L, "Lucas", "Gómez"));
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(claimsAnalystRepository.findById(9L)).thenReturn(Optional.of(analyst(9L, "Sofía", "Martínez")));

        CaseResponse response = caseService.assignAnalyst(1L, 9L);

        assertThat(response.assignedAnalystId()).isEqualTo(9L);
        assertThat(response.assignedAnalystName()).isEqualTo("Sofía Martínez");
    }

    @Test
    void assignAnalyst_unknownCase_throwsNotFound() {
        when(caseRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> caseService.assignAnalyst(99L, 7L))
                .isInstanceOf(CaseNotFoundException.class);
    }

    @Test
    void assignAnalyst_analystOutsideTheTenant_throwsNotFound() {
        // claims_analyst es por esquema: un analista de otra aseguradora no está en esta tabla.
        // Es lo que hace que el aislamiento no necesite un chequeo aparte.
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(claimsAnalystRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> caseService.assignAnalyst(1L, 404L))
                .isInstanceOf(AnalystNotFoundException.class);
        verify(caseRepository, never()).save(any());
    }

    @Test
    void unassignAnalyst_clearsOwnerAndRecordsIt() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setAnalyst(analyst(7L, "Lucas", "Gómez"));
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(accessPolicy.currentAssignmentActor()).thenReturn(StatusChangeActor.ANALYST);

        CaseResponse response = caseService.unassignAnalyst(1L);

        assertThat(response.assignedAnalystId()).isNull();
        assertThat(response.assignedAnalystName()).isNull();
        verify(caseStatusService).recordAssignment(entity, StatusChangeActor.ANALYST,
                "expediente liberado (estaba asignado a Lucas Gómez)");
    }

    @Test
    void unassignAnalyst_alreadyUnassigned_isIdempotent() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(accessPolicy.currentAssignmentActor()).thenReturn(StatusChangeActor.ANALYST);

        CaseResponse response = caseService.unassignAnalyst(1L);

        assertThat(response.assignedAnalystId()).isNull();
        verify(caseStatusService).recordAssignment(entity, StatusChangeActor.ANALYST, "expediente liberado");
    }

    private static ClaimsAnalyst analyst(Long id, String name, String surname) {
        return ClaimsAnalyst.builder()
                .id(id)
                .name(name)
                .surname(surname)
                .email(name.toLowerCase() + "@arbiter.test")
                .build();
    }

    @Test
    void getCase_includesStatusHistoryWithTimestamps() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(caseStatusService.history(1L)).thenReturn(List.of(
                CaseStatusHistory.builder()
                        .caseId(1L).initialStatus(null)
                        .finalStatus(CaseStates.of(CaseStatus.PENDING_CLASSIFICATION))
                        .actor(StatusChangeActor.INSURED).reason("denuncia registrada").build(),
                CaseStatusHistory.builder()
                        .caseId(1L).initialStatus(CaseStates.of(CaseStatus.PENDING_CLASSIFICATION))
                        .finalStatus(CaseStates.of(CaseStatus.PENDING_ANALYST_REVIEW))
                        .actor(StatusChangeActor.SYSTEM).reason("clasificación: LLM_RECOMIENDA_APROBAR").build()
        ));

        CaseResponse response = caseService.getCase(1L);

        assertThat(response.statusHistory()).hasSize(2);
        assertThat(response.statusHistory().get(0).fromStatus()).isNull();
        assertThat(response.statusHistory().get(0).toStatus()).isEqualTo(CaseStatus.PENDING_CLASSIFICATION);
        assertThat(response.statusHistory().get(1).actor()).isEqualTo(StatusChangeActor.SYSTEM);
    }

    @Test
    void recordAnalystDecision_approve_transitionsToApproved() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setAnalyst(ClaimsAnalyst.builder().id(7L).build());
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        authenticateAs("analista@example.com");
        when(claimsAnalystRepository.findByEmail("analista@example.com"))
                .thenReturn(Optional.of(ClaimsAnalyst.builder().id(7L).build()));
        AnalystDecisionRequest request = new AnalystDecisionRequest(null, "APPROVE", "Documentación completa", null);

        caseService.recordAnalystDecision(1L, request);

        // Se reenvía con el analista resuelto del JWT (no del request) y el contador de
        // reintentos del expediente, que el frontend no conoce: case_classification.
        // classification_attempts se congela con ese valor.
        verify(claimsAnalysisClient).forwardAnalystDecision(1L,
                new AnalystDecisionRequest(7L, "APPROVE", "Documentación completa", entity.getClassificationAttempts()));
        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.APPROVED),
                eq(StatusChangeActor.ANALYST), any());
    }

    @Test
    void recordAnalystDecision_unknownDecision_throwsAndDoesNotForward() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> caseService.recordAnalystDecision(1L,
                new AnalystDecisionRequest(null, "DERIVAR", null, null)))
                .isInstanceOf(InvalidAnalystDecisionException.class);

        verify(claimsAnalysisClient, never()).forwardAnalystDecision(any(), any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void recordAnalystDecision_caseNotUnderReview_throwsBeforeForwarding() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> caseService.recordAnalystDecision(1L,
                new AnalystDecisionRequest(null, "APPROVE", null, null)))
                .isInstanceOf(InvalidStatusTransitionException.class);

        verify(claimsAnalysisClient, never()).forwardAnalystDecision(any(), any());
    }

    @Test
    void recordAnalystDecision_callerHasNoAnalystProfile_throws() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        authenticateAs("referente@example.com");
        when(claimsAnalystRepository.findByEmail("referente@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> caseService.recordAnalystDecision(1L,
                new AnalystDecisionRequest(null, "APPROVE", null, null)))
                .isInstanceOf(AnalystProfileNotFoundException.class);

        verify(claimsAnalysisClient, never()).forwardAnalystDecision(any(), any());
    }

    /**
     * Decidir es lo que gana el que tiene el expediente asignado. Sin analista asignado no hay
     * a quién dejarle decidir — fuerza el orden asignar → decidir en vez de que la decisión
     * funcione como una asignación implícita.
     */
    @Test
    void recordAnalystDecision_caseNotAssigned_throwsBeforeForwarding() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        authenticateAs("analista@example.com");
        when(claimsAnalystRepository.findByEmail("analista@example.com"))
                .thenReturn(Optional.of(ClaimsAnalyst.builder().id(7L).build()));

        assertThatThrownBy(() -> caseService.recordAnalystDecision(1L,
                new AnalystDecisionRequest(null, "APPROVE", null, null)))
                .isInstanceOf(CaseNotAssignedException.class);

        verify(claimsAnalysisClient, never()).forwardAnalystDecision(any(), any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    /** Otro analista con rol válido y perfil propio, pero el expediente no es suyo. */
    @Test
    void recordAnalystDecision_assignedToAnotherAnalyst_throwsBeforeForwarding() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setAnalyst(ClaimsAnalyst.builder().id(7L).build());
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        authenticateAs("otro.analista@example.com");
        when(claimsAnalystRepository.findByEmail("otro.analista@example.com"))
                .thenReturn(Optional.of(ClaimsAnalyst.builder().id(9L).build()));

        assertThatThrownBy(() -> caseService.recordAnalystDecision(1L,
                new AnalystDecisionRequest(null, "APPROVE", null, null)))
                .isInstanceOf(CaseAssignedToAnotherAnalystException.class);

        verify(claimsAnalysisClient, never()).forwardAnalystDecision(any(), any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void addDocumentsAndReclassify_resetsClassificationAttempts() {
        Case entity = caseRecord(1L, CaseStatus.AWAITING_DOCUMENTATION);
        entity.setClassificationAttempts(115);
        entity.setDeterministicFastTrack(Boolean.FALSE);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        caseService.addDocumentsAndReclassify(1L, Map.of());

        assertThat(entity.getClassificationAttempts()).isZero();
        // false y no null: la columna was_fast_track es NOT NULL, así que el null que se seteaba
        // antes reventaba al persistir contra el esquema real. Para el que lee es lo mismo,
        // wasFastTracked() trata los dos igual.
        assertThat(entity.getDeterministicFastTrack()).isFalse();
    }

    @Test
    void getCase_nullConfidence_defaultsToZero() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        CaseResponse response = caseService.getCase(1L);

        assertThat(response.analysisConfidence()).isEqualTo(0.0);
    }

    @Test
    void createCase_withDocuments_persistsEachDocument() {
        CaseRequest request = caseRequest();
        Case saved = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        stubReferenceResolution();
        when(caseRepository.save(any(Case.class))).thenReturn(saved);
        when(caseDocumentRepository.findByCaseIdAndType(eq(1L), any())).thenReturn(Optional.empty());
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        MockMultipartFile police = new MockMultipartFile(
                "police_report", "denuncia.pdf", "application/pdf", "pdf-bytes".getBytes());

        caseService.createCase(request, Map.of("police_report", police));

        ArgumentCaptor<CaseDocument> captor = ArgumentCaptor.forClass(CaseDocument.class);
        verify(caseDocumentRepository).save(captor.capture());
        CaseDocument stored = captor.getValue();
        assertThat(stored.getCaseId()).isEqualTo(1L);
        assertThat(stored.getType()).isEqualTo("police_report");
        assertThat(stored.getFilename()).isEqualTo("denuncia.pdf");
        assertThat(stored.getContentType()).isEqualTo("application/pdf");
        assertThat(stored.getContent()).isEqualTo("pdf-bytes".getBytes());
    }

    @Test
    void createCase_withDocuments_ignoresCasePayloadKey() {
        // The frontend sends the case JSON itself as a Blob under the "case" multipart key
        // (see ExpedienteService.create), and Spring's Map<String, MultipartFile> binding picks
        // it up alongside the real documents. It must never be persisted as a document — if it
        // were, it'd travel to classification-service's OCR step, which tries to read every
        // non-PDF attachment as an image and fails ("Failed to load image or audio file").
        CaseRequest request = caseRequest();
        Case saved = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        stubReferenceResolution();
        when(caseRepository.save(any(Case.class))).thenReturn(saved);
        when(caseDocumentRepository.findByCaseIdAndType(eq(1L), any())).thenReturn(Optional.empty());
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        MockMultipartFile casePayload = new MockMultipartFile(
                "case", "blob", "application/json", "{\"branch\":\"Celulares\"}".getBytes());
        MockMultipartFile photo = new MockMultipartFile(
                "item_photo", "foto.png", "image/png", "png-bytes".getBytes());

        caseService.createCase(request, Map.of("case", casePayload, "item_photo", photo));

        ArgumentCaptor<CaseDocument> captor = ArgumentCaptor.forClass(CaseDocument.class);
        verify(caseDocumentRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("item_photo");
    }

    @Test
    void addDocuments_replacesDocumentOfSameType() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        CaseDocument existing = CaseDocument.builder()
                .id(7L).caseId(1L).type("item_photo")
                .filename("old.jpg").contentType("image/jpeg").content("old".getBytes())
                .build();
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(caseDocumentRepository.findByCaseIdAndType(1L, "item_photo")).thenReturn(Optional.of(existing));
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of(existing));
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        MockMultipartFile newPhoto = new MockMultipartFile(
                "item_photo", "new.jpg", "image/jpeg", "new".getBytes());

        caseService.addDocumentsAndReclassify(1L, Map.of("item_photo", newPhoto));

        ArgumentCaptor<CaseDocument> captor = ArgumentCaptor.forClass(CaseDocument.class);
        verify(caseDocumentRepository).save(captor.capture());
        CaseDocument stored = captor.getValue();
        assertThat(stored.getId()).isEqualTo(7L); // updates the existing row, no new document
        assertThat(stored.getFilename()).isEqualTo("new.jpg");
        assertThat(stored.getContent()).isEqualTo("new".getBytes());
    }

    // ───────────────── checkEligibility ─────────────────
    // Same resolution createCase does up to (not including) building the Case — see
    // CaseServiceImpl#checkEligibilityInIssuingTenant's javadoc. What's under test here is that a
    // PolicyNotEligibleException/PolicyInsuredMismatchException becomes eligible=false instead of
    // propagating, and that nothing gets persisted either way.

    private EligibilityCheckRequest eligibilityRequest() {
        return new EligibilityCheckRequest(
                CALLER_DNI, "POL-CEL-2024-001", LocalDateTime.of(2026, 6, 13, 19, 45), null);
    }

    @Test
    void checkEligibility_eligibleWhenValidationPasses() {
        stubPolicyAndInsuredResolution();

        EligibilityCheckResponse response = caseService.checkEligibility(eligibilityRequest());

        assertThat(response.eligible()).isTrue();
        assertThat(response.reason()).isNull();
        verify(caseRepository, never()).save(any());
    }

    @Test
    void checkEligibility_notEligibleWhenPolicyValidationFails() {
        stubPolicyAndInsuredResolution();
        doThrow(new PolicyNotEligibleException("La póliza no estaba vigente el 2026-06-13."))
                .when(policyEligibilityValidator).validate(any(), any(), any(), any(), any());

        EligibilityCheckResponse response = caseService.checkEligibility(eligibilityRequest());

        assertThat(response.eligible()).isFalse();
        assertThat(response.reason()).contains("no estaba vigente");
        verify(caseRepository, never()).save(any());
    }

    @Test
    void checkEligibility_notEligibleWhenPolicyBelongsToSomeoneElse() {
        Insured insured = CaseFixtures.insured(CALLER_DNI, "Laura", "Fernández");
        insured.setId(1L);
        Policy someoneElsesPolicy = CaseFixtures.policy("POL-CEL-2024-001", "Celular Protegido Básico");
        someoneElsesPolicy.setInsuredId(2L);
        when(referenceResolver.resolveInsured(CALLER_DNI)).thenReturn(insured);
        when(referenceResolver.resolvePolicy("POL-CEL-2024-001", 1L)).thenReturn(someoneElsesPolicy);

        EligibilityCheckResponse response = caseService.checkEligibility(eligibilityRequest());

        assertThat(response.eligible()).isFalse();
        assertThat(response.reason()).contains("POL-CEL-2024-001");
        verifyNoInteractions(policyEligibilityValidator);
    }

    @Test
    void checkEligibility_rejectsCheckingForSomeoneElse() {
        EligibilityCheckRequest request = new EligibilityCheckRequest(
                "99.999.999", "POL-CEL-2024-001", LocalDateTime.of(2026, 6, 13, 19, 45), null);

        assertThatThrownBy(() -> caseService.checkEligibility(request))
                .isInstanceOf(InsuredIdentityMismatchException.class);

        verifyNoInteractions(referenceResolver, policyEligibilityValidator);
    }

    private CaseRequest caseRequest() {
        return new CaseRequest(
                "Celulares",
                "Celular Protegido Básico",
                "Robo en vía pública",
                "Motorola Edge 50 Pro",
                "40.123.456",
                "POL-CEL-2024-001",
                "Me robaron el celular en la estación de subte",
                LocalDateTime.of(2026, 6, 13, 19, 45),
                "Av. Rivadavia 1234",
                "Buenos Aires", // province
                "CABA", // locality
                null, // policeReportAt
                new BigDecimal("150000"),
                null, null,
                "test@example.com",
                "11-5555-0000"
        );
    }

    private Case caseRecord(Long id, CaseStatus status) {
        return Case.builder()
                .id(id)
                .claimCause(CaseFixtures.claimCause("Celulares", "Robo en vía pública"))
                .declaredItem("Motorola Edge 50 Pro")
                .insured(CaseFixtures.insured("40.123.456", "Laura", "Fernández"))
                .policy(CaseFixtures.policy("POL-CEL-2024-001", "Celular Protegido Básico"))
                .coverage(CaseFixtures.coverage("Celulares"))
                .description("Me robaron el celular en la estación de subte")
                .occurredAt(LocalDateTime.of(2026, 6, 13, 19, 45))
                .eventAddress("Estación Congreso, CABA")
                .claimedAmount(new BigDecimal("150000"))
                .responseDeadline(LocalDate.of(2026, 7, 13))
                .currentStatus(CaseStates.of(status))
                .build();
    }

    /**
     * checkEligibility only resolves policy + insured (see
     * CaseServiceImpl#checkEligibilityInIssuingTenant) — no claim cause, no declared details.
     */
    private Insured stubPolicyAndInsuredResolution() {
        Policy policy = CaseFixtures.policy("POL-CEL-2024-001", "Celular Protegido Básico");
        Insured insured = CaseFixtures.insured("40.123.456", "Laura", "Fernández");
        when(referenceResolver.resolvePolicy(any(), any())).thenReturn(policy);
        when(referenceResolver.resolveInsured(any())).thenReturn(insured);
        return insured;
    }

    /**
     * Every createCase test needs the four lookups to resolve; what varies is only what each one
     * then asserts. Mirrors the graph {@link #caseRecord} builds.
     */
    private void stubReferenceResolution() {
        Insured insured = stubPolicyAndInsuredResolution();
        when(referenceResolver.applyDeclaredDetails(any(), any())).thenReturn(insured);
        when(referenceResolver.resolveClaimCause(any(), any()))
                .thenReturn(CaseFixtures.claimCause("Celulares", "Robo en vía pública"));
    }
}
