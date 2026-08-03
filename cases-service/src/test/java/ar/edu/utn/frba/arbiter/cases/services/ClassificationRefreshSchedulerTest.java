package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.cases.support.CaseStates;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    @Mock
    private InsurerRepository insurerRepository;

    private ClassificationRefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ClassificationRefreshScheduler(
                caseRepository, caseStatusService, claimsAnalysisClient, insurerRepository);
        // The sweep is per tenant now, so every test needs at least one insurer to sweep.
        // A single one keeps these cases testing what they always tested; the multi-tenant
        // behaviour has its own tests below. Lenient because those two override this stub
        // before it is ever called, which strict stubs would otherwise flag.
        lenient().when(insurerRepository.findByActiveTrue())
                .thenReturn(List.of(insurer(1L, "arbiter_bbva")));
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

    @Test
    void sweepsEveryActiveInsurer_withThatTenantResolvedEachTime() {
        when(insurerRepository.findByActiveTrue())
                .thenReturn(List.of(insurer(1L, "arbiter_bbva"), insurer(2L, "arbiter_provincia")));
        // Capture the tenant in effect at the moment each schema is queried: proving the
        // sweep switches tenants is the whole point of the change.
        List<String> tenantsSeen = new ArrayList<>();
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenAnswer(invocation -> {
            tenantsSeen.add(TenantContext.get());
            return List.of();
        });

        scheduler.refreshPendingCases();

        assertThat(tenantsSeen).containsExactly("arbiter_bbva", "arbiter_provincia");
        // And nothing leaks past the sweep.
        assertThat(TenantContext.get()).isEqualTo(TenantContext.COMMON_SCHEMA);
    }

    @Test
    void oneInsurerFailing_doesNotStopTheRest() {
        when(insurerRepository.findByActiveTrue())
                .thenReturn(List.of(insurer(1L, "arbiter_bbva"), insurer(2L, "arbiter_provincia")));
        Case entity = pendingCase(0);
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION))
                .thenThrow(new RuntimeException("schema unreachable"))
                .thenReturn(List.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenReturn(true);

        scheduler.refreshPendingCases();

        // The second insurer was still swept despite the first one blowing up.
        verify(claimsAnalysisClient).refreshClassification(entity);
        assertThat(TenantContext.get()).isEqualTo(TenantContext.COMMON_SCHEMA);
    }

    private Insurer insurer(Long id, String schemaName) {
        return Insurer.builder()
                .id(id)
                .legalName("Seguros " + id + " S.A.")
                .name("Seguros " + id)
                .taxId("30-0000000" + id + "-0")
                .active(true)
                .schemaName(schemaName)
                .build();
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
                .claimCause(CaseFixtures.claimCause("Celulares", "Robo en vía pública"))
                .declaredItem("Motorola Edge 50 Pro")
                .insured(CaseFixtures.insured("40.123.456", "Laura", "Fernández"))
                .policy(CaseFixtures.policy("POL-CEL-2024-001", "Celular Protegido Básico"))
                .coverage(CaseFixtures.coverage("Celulares"))
                .description("Test case")
                .occurredAt(LocalDateTime.of(2026, 6, 13, 19, 45))
                .eventAddress("CABA")
                .claimedAmount(new BigDecimal("150000"))
                .currentStatus(CaseStates.of(CaseStatus.PENDING_CLASSIFICATION))
                .classificationAttempts(attempts)
                .build();
    }
}
