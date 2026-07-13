package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseEntity;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
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
    private ClaimsAnalysisClient claimsAnalysisClient;

    @InjectMocks
    private CaseServiceImpl caseService;

    @Test
    void createCase_persistsEntityAndTriggersClassification() {
        CaseRequest request = caseRequest();
        CaseEntity saved = caseEntity(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseRepository.save(any(CaseEntity.class))).thenReturn(saved);
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        CaseResponse response = caseService.createCase(request, null);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo(CaseStatus.PENDING_CLASSIFICATION);
        assertThat(response.branch()).isEqualTo("Celulares");

        ArgumentCaptor<CaseEntity> captor = ArgumentCaptor.forClass(CaseEntity.class);
        verify(caseRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CaseStatus.PENDING_CLASSIFICATION);

        verify(claimsAnalysisClient).analyzeAndPersist(eq(saved), eq(List.of()));
    }

    @Test
    void createCase_mapsAllFieldsFromRequest() {
        CaseRequest request = caseRequest();
        when(caseRepository.save(any(CaseEntity.class))).thenAnswer(inv -> {
            CaseEntity e = inv.getArgument(0);
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
    void getCase_returnsResponse() {
        CaseEntity entity = caseEntity(1L, CaseStatus.PENDING_ANALYST_REVIEW);
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
    void getCase_notFound_throwsCaseNotFoundException() {
        when(caseRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> caseService.getCase(999L))
                .isInstanceOf(CaseNotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    void addDocumentsAndReclassify_resetsClassificationAndRetriggers() {
        CaseEntity entity = caseEntity(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setAnalysisClassification(Classification.LLM_RECOMIENDA_APROBAR);
        entity.setAnalysisConfidence(0.9);
        entity.setAnalysisDetail("Some detail");
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(caseRepository.save(any(CaseEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        CaseResponse response = caseService.addDocumentsAndReclassify(1L, Map.of());

        assertThat(response.status()).isEqualTo(CaseStatus.PENDING_CLASSIFICATION);
        assertThat(response.analysisClassification()).isNull();
        assertThat(response.analysisConfidence()).isEqualTo(0.0);
        assertThat(response.analysisDetail()).isNull();

        verify(caseRepository).save(entity);
        verify(claimsAnalysisClient).analyzeAndPersist(eq(entity), eq(List.of()));
    }

    @Test
    void addDocumentsAndReclassify_notFound_throwsCaseNotFoundException() {
        when(caseRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> caseService.addDocumentsAndReclassify(999L, Map.of()))
                .isInstanceOf(CaseNotFoundException.class);
    }

    @Test
    void getCase_nullConfidence_defaultsToZero() {
        CaseEntity entity = caseEntity(1L, CaseStatus.PENDING_CLASSIFICATION);
        entity.setAnalysisConfidence(null);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        CaseResponse response = caseService.getCase(1L);

        assertThat(response.analysisConfidence()).isEqualTo(0.0);
    }

    @Test
    void createCase_withDocuments_persistsEachDocument() {
        CaseRequest request = caseRequest();
        CaseEntity saved = caseEntity(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseRepository.save(any(CaseEntity.class))).thenReturn(saved);
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
    void addDocuments_replacesDocumentOfSameType() {
        CaseEntity entity = caseEntity(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        CaseDocument existing = CaseDocument.builder()
                .id(7L).caseId(1L).type("item_photo")
                .filename("old.jpg").contentType("image/jpeg").content("old".getBytes())
                .build();
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(caseRepository.save(any(CaseEntity.class))).thenAnswer(inv -> inv.getArgument(0));
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
                new BigDecimal("150000")
        );
    }

    private CaseEntity caseEntity(Long id, CaseStatus status) {
        return CaseEntity.builder()
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
