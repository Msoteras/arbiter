package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClassificationRefreshSchedulerTest {

    @Mock
    private CaseRepository caseRepository;

    @Mock
    private CaseStatusService caseStatusService;

    @Mock
    private ClaimsAnalysisClient claimsAnalysisClient;

    private ClassificationRefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ClassificationRefreshScheduler(caseRepository, caseStatusService, claimsAnalysisClient);
        setMaxAttempts(3);
    }

    @Test
    void noPendingCases_doesNothing() {
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of());

        scheduler.refreshPendingCases();

        verifyNoInteractions(claimsAnalysisClient);
        verify(caseRepository, never()).save(any());
    }

    @Test
    void resolvedCase_doesNotIncrementAttempts() {
        Case entity = pendingCase(0);
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenReturn(true);

        scheduler.refreshPendingCases();

        verify(caseRepository, never()).save(any());
        assertThat(entity.getClassificationAttempts()).isEqualTo(0);
    }

    @Test
    void unresolvedCase_incrementsAttempts() {
        Case entity = pendingCase(0);
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenReturn(false);

        scheduler.refreshPendingCases();

        ArgumentCaptor<Case> captor = ArgumentCaptor.forClass(Case.class);
        verify(caseRepository).save(captor.capture());
        assertThat(captor.getValue().getClassificationAttempts()).isEqualTo(1);
        assertThat(captor.getValue().getStatus()).isEqualTo(CaseStatus.PENDING_CLASSIFICATION);
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void maxAttemptsReached_marksClassificationFailed() {
        Case entity = pendingCase(2);
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenReturn(false);

        scheduler.refreshPendingCases();

        assertThat(entity.getClassificationAttempts()).isEqualTo(3);
        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.CLASSIFICATION_FAILED),
                eq(StatusChangeActor.SYSTEM), any());
        verify(caseRepository, never()).save(any());
    }

    @Test
    void exceptionDuringRefresh_incrementsAttempts() {
        Case entity = pendingCase(0);
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenThrow(new RuntimeException("connection refused"));

        scheduler.refreshPendingCases();

        ArgumentCaptor<Case> captor = ArgumentCaptor.forClass(Case.class);
        verify(caseRepository).save(captor.capture());
        assertThat(captor.getValue().getClassificationAttempts()).isEqualTo(1);
    }

    @Test
    void exceptionAtMaxAttempts_marksClassificationFailed() {
        Case entity = pendingCase(2);
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenThrow(new RuntimeException("timeout"));

        scheduler.refreshPendingCases();

        assertThat(entity.getClassificationAttempts()).isEqualTo(3);
        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.CLASSIFICATION_FAILED),
                eq(StatusChangeActor.SYSTEM), any());
    }

    @Test
    void multiplePendingCases_processesEachIndependently() {
        Case resolved = pendingCase(0);
        Case unresolved = pendingCase(1);
        Case failing = pendingCase(2);

        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION))
                .thenReturn(List.of(resolved, unresolved, failing));
        when(claimsAnalysisClient.refreshClassification(resolved)).thenReturn(true);
        when(claimsAnalysisClient.refreshClassification(unresolved)).thenReturn(false);
        when(claimsAnalysisClient.refreshClassification(failing)).thenReturn(false);

        scheduler.refreshPendingCases();

        assertThat(resolved.getClassificationAttempts()).isEqualTo(0);
        assertThat(unresolved.getClassificationAttempts()).isEqualTo(2);
        assertThat(unresolved.getStatus()).isEqualTo(CaseStatus.PENDING_CLASSIFICATION);
        assertThat(failing.getClassificationAttempts()).isEqualTo(3);

        verify(caseRepository, times(1)).save(unresolved);   // only the still-pending one
        verify(caseStatusService).transition(eq(failing), eq(CaseStatus.CLASSIFICATION_FAILED),
                eq(StatusChangeActor.SYSTEM), any());
    }

    private void setMaxAttempts(int maxAttempts) {
        try {
            var field = ClassificationRefreshScheduler.class.getDeclaredField("maxAttempts");
            field.setAccessible(true);
            field.setInt(scheduler, maxAttempts);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Case pendingCase(int attempts) {
        return Case.builder()
                .id((long) (attempts + 1))
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause("Robo en vía pública")
                .insuredItem("Motorola Edge 50 Pro")
                .insuredId("40.123.456")
                .policyNumber("POL-CEL-2024-001")
                .description("Test case")
                .eventDate(LocalDateTime.of(2026, 6, 13, 19, 45))
                .eventLocation("CABA")
                .claimedAmount(new BigDecimal("150000"))
                .status(CaseStatus.PENDING_CLASSIFICATION)
                .classificationAttempts(attempts)
                .build();
    }
}
