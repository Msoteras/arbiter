package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.AnalystNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.AnalystProfileNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidAnalystDecisionException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidStatusTransitionException;
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
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimsAnalystRepository;
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
import java.time.LocalDate;
import java.time.LocalDateTime;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CaseServiceImplTest {

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
    private CaseAccessPolicy accessPolicy;

    @Mock
    private InsuredCaseAggregator insuredCaseAggregator;

    @Mock
    private PolicyTenantLocator policyTenantLocator;

    @InjectMocks
    private CaseServiceImpl caseService;

    /**
     * Por defecto no hay clasificación joineada. Es lo que devuelve el repositorio para un
     * expediente recién creado, y deja que cada test que sí la necesita la sobrescriba.
     */
    @BeforeEach
    void noAnalysisByDefault() {
        lenient().when(caseAnalysisRepository.findByCaseId(any())).thenReturn(CaseAnalysis.none());
        lenient().when(caseAnalysisRepository.findByCaseIds(any())).thenReturn(Map.of());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
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

        verify(claimsAnalysisClient).analyzeAndPersist(eq(saved), eq(List.of()));
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
        // pep/imageConsent/contacto describen a la persona, no al siniestro: desde el DER viven en
        // `insured`. El alta solo los reenvía — que efectivamente se apliquen es de
        // CaseReferenceResolverTest.
        CaseRequest request = new CaseRequest(
                "Celulares", "Celular Protegido Básico", "Robo en vía pública",
                "Motorola Edge 50 Pro", "40.123.456", "POL-CEL-2024-001",
                "Me robaron el celular en la estación de subte",
                LocalDateTime.of(2026, 6, 13, 19, 45), "Estación Congreso, CABA",
                null, // policeReportAt
                new BigDecimal("150000"),
                false,
                true, // imageConsent: el asegurado aceptó el análisis forense de sus imágenes (H0009)
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
        when(referenceResolver.resolvePolicy("POL-CEL-2024-001"))
                .thenThrow(new UnresolvedCaseReferenceException("policy", "POL-CEL-2024-001"));

        assertThatThrownBy(() -> caseService.createCase(request, null))
                .isInstanceOf(UnresolvedCaseReferenceException.class)
                .hasMessageContaining("POL-CEL-2024-001");

        verify(caseRepository, never()).save(any());
        verify(claimsAnalysisClient, never()).analyzeAndPersist(any(), any());
    }

    @Test
    void getCase_joinsTheClassificationFromLlmAnalysis() {
        // La recomendación ya no es columna de `cases`: sale del join, y el detalle se arma
        // concatenando los motivos (llm_reason).
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
        assertThat(response.analysisDetail()).isEqualTo("Monto bajo, Primer siniestro");
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
        assertThat(response.analysisDetail()).isEqualTo("Fast track classification available");
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
        assertThat(response.analysisDetail()).isNull();
    }

    @Test
    void getCase_includesCachedForensicReport() {
        ImageForensicReport.InternalMatch match =
                new ImageForensicReport.InternalMatch(4L, "item_photo", 0.97);
        ImageForensicReport.ImageFinding finding =
                new ImageForensicReport.ImageFinding("item_photo-0", "item_photo", List.of(match), null);
        ImageForensicReport report = new ImageForensicReport(1, 0, List.of(finding));

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
        assertThat(response.analysisDetail()).isNull();

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

        caseService.assignAnalyst(1L, 7L);

        verify(caseStatusService).recordAssignment(entity, "expediente asignado a Lucas Gómez");
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

        CaseResponse response = caseService.unassignAnalyst(1L);

        assertThat(response.assignedAnalystId()).isNull();
        assertThat(response.assignedAnalystName()).isNull();
        verify(caseStatusService).recordAssignment(entity,
                "expediente liberado (estaba asignado a Lucas Gómez)");
    }

    @Test
    void unassignAnalyst_alreadyUnassigned_isIdempotent() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        CaseResponse response = caseService.unassignAnalyst(1L);

        assertThat(response.assignedAnalystId()).isNull();
        verify(caseStatusService).recordAssignment(entity, "expediente liberado");
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
                "Estación Congreso, CABA",
                null, // policeReportAt
                new BigDecimal("150000"),
                false,
                false,
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
     * Every createCase test needs the four lookups to resolve; what varies is only what each one
     * then asserts. Mirrors the graph {@link #caseRecord} builds.
     */
    private void stubReferenceResolution() {
        Policy policy = CaseFixtures.policy("POL-CEL-2024-001", "Celular Protegido Básico");
        Insured insured = CaseFixtures.insured("40.123.456", "Laura", "Fernández");
        when(referenceResolver.resolvePolicy(any())).thenReturn(policy);
        when(referenceResolver.resolveInsured(any())).thenReturn(insured);
        when(referenceResolver.applyDeclaredDetails(any(), any())).thenReturn(insured);
        when(referenceResolver.resolveClaimCause(any(), any()))
                .thenReturn(CaseFixtures.claimCause("Celulares", "Robo en vía pública"));
    }
}
