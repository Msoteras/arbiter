package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidAnalystDecisionException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidStatusTransitionException;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseStatusHistory;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
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
    private AnalystDirectory analystDirectory;

    @InjectMocks
    private CaseServiceImpl caseService;

    @Test
    void createCase_persistsEntityAndTriggersClassification() {
        CaseRequest request = caseRequest();
        Case saved = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
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
    void createCase_persistsImageConsent() {
        CaseRequest request = new CaseRequest(
                "Celulares", "Celular Protegido Básico", "Robo en vía pública",
                "Motorola Edge 50 Pro", "40.123.456", "POL-CEL-2024-001",
                "Me robaron el celular en la estación de subte",
                LocalDateTime.of(2026, 6, 13, 19, 45), "Estación Congreso, CABA",
                new BigDecimal("150000"),
                false,
                true, // imageConsent: el asegurado aceptó el análisis forense de sus imágenes (H0009)
                "test@example.com", "11-5555-0000"
        );
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
        assertThat(captor.getValue().isImageConsent()).isTrue();
    }

    @Test
    void getCase_returnsResponse() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setAnalysisClassification(Classification.FAST_TRACK);
        entity.setAnalysisConfidence(1.0);
        entity.setAnalysisDetail("Low amount, first claim");
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        CaseResponse response = caseService.getCase(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo(CaseStatus.PENDING_ANALYST_REVIEW);
        assertThat(response.analysisClassification()).isEqualTo(Classification.FAST_TRACK);
        assertThat(response.analysisConfidence()).isEqualTo(1.0);
        assertThat(response.analysisDetail()).isEqualTo("Low amount, first claim");
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
        entity.setAnalysisClassification(Classification.LLM_RECOMIENDA_APROBAR);
        entity.setAnalysisConfidence(0.9);
        entity.setAnalysisDetail("Some detail");
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        // transition drives the status change; mirror it so the response reflects the new state
        when(caseStatusService.transition(any(), eq(CaseStatus.PENDING_CLASSIFICATION), any(), any()))
                .thenAnswer(inv -> {
                    Case e = inv.getArgument(0);
                    e.setStatus(CaseStatus.PENDING_CLASSIFICATION);
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

        Page<CaseResponse> response = caseService.listCases(null, null, null, null, null, null, null, null, null, pageable);

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
                CaseStatus.PENDING_ANALYST_REVIEW, null, null, null, null, null, null, null, null, pageable);

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
                null, null, null, "40.123.456", null, null, null, null, null, pageable);

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
                null, null, null, null, null, null, "POL-CEL", null, null, pageable);

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
                null, null, null, null, null, null, null, RiskBand.HIGH, null, pageable);

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
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), null, null, null, pageable);

        assertThat(response.getContent()).hasSize(1);
        verify(caseRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void listCases_noResults_returnsEmptyPage() {
        Pageable pageable = PageRequest.of(0, 20);
        when(caseRepository.findAll(isNull(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        Page<CaseResponse> response = caseService.listCases(null, null, null, null, null, null, null, null, null, pageable);

        assertThat(response.getContent()).isEmpty();
        assertThat(response.getTotalElements()).isZero();
    }

    @Test
    void assignAnalyst_setsOwnerAndCachesResolvedName() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(analystDirectory.analystName(7L)).thenReturn("Lucas Gómez");

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
        when(analystDirectory.analystName(7L)).thenReturn("Lucas Gómez");

        caseService.assignAnalyst(1L, 7L);

        assertThat(entity.getStatus()).isEqualTo(CaseStatus.PENDING_ANALYST_REVIEW);
        verify(caseStatusService, never()).transition(any(), any(), any(), anyString());
    }

    @Test
    void assignAnalyst_recordsAssignmentInHistory() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(analystDirectory.analystName(7L)).thenReturn("Lucas Gómez");

        caseService.assignAnalyst(1L, 7L);

        verify(caseStatusService).recordAssignment(entity, "expediente asignado a Lucas Gómez");
    }

    @Test
    void assignAnalyst_reassigning_replacesThePreviousOwner() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setAssignedAnalystId(7L);
        entity.setAssignedAnalystName("Lucas Gómez");
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(analystDirectory.analystName(9L)).thenReturn("Sofía Martínez");

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
    void unassignAnalyst_clearsOwnerAndRecordsIt() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setAssignedAnalystId(7L);
        entity.setAssignedAnalystName("Lucas Gómez");
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

    @Test
    void getCase_includesStatusHistoryWithTimestamps() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(caseStatusService.history(1L)).thenReturn(List.of(
                CaseStatusHistory.builder()
                        .caseId(1L).fromStatus(null).toStatus(CaseStatus.PENDING_CLASSIFICATION)
                        .actor(StatusChangeActor.INSURED).reason("denuncia registrada").build(),
                CaseStatusHistory.builder()
                        .caseId(1L).fromStatus(CaseStatus.PENDING_CLASSIFICATION)
                        .toStatus(CaseStatus.PENDING_ANALYST_REVIEW)
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
        AnalystDecisionRequest request = new AnalystDecisionRequest("analyst-1", "APPROVE");

        caseService.recordAnalystDecision(1L, request);

        verify(claimsAnalysisClient).forwardAnalystDecision(1L, request);
        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.APPROVED),
                eq(StatusChangeActor.ANALYST), any());
    }

    @Test
    void recordAnalystDecision_unknownDecision_throwsAndDoesNotForward() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> caseService.recordAnalystDecision(1L,
                new AnalystDecisionRequest("analyst-1", "DERIVAR")))
                .isInstanceOf(InvalidAnalystDecisionException.class);

        verify(claimsAnalysisClient, never()).forwardAnalystDecision(any(), any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void recordAnalystDecision_caseNotUnderReview_throwsBeforeForwarding() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> caseService.recordAnalystDecision(1L,
                new AnalystDecisionRequest("analyst-1", "APPROVE")))
                .isInstanceOf(InvalidStatusTransitionException.class);

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
        assertThat(entity.getDeterministicFastTrack()).isNull();
    }

    @Test
    void getCase_nullConfidence_defaultsToZero() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        entity.setAnalysisConfidence(null);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        CaseResponse response = caseService.getCase(1L);

        assertThat(response.analysisConfidence()).isEqualTo(0.0);
    }

    @Test
    void createCase_withDocuments_persistsEachDocument() {
        CaseRequest request = caseRequest();
        Case saved = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
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
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause("Robo en vía pública")
                .insuredItem("Motorola Edge 50 Pro")
                .insuredId("40.123.456")
                .policyNumber("POL-CEL-2024-001")
                .description("Me robaron el celular en la estación de subte")
                .eventDate(LocalDateTime.of(2026, 6, 13, 19, 45))
                .eventLocation("Estación Congreso, CABA")
                .claimedAmount(new BigDecimal("150000"))
                .status(status)
                .build();
    }
}
