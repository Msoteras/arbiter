package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.cases.support.CaseStates;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationFailureReason;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClassificationRefreshSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-10-08T15:00:00Z");

    @Mock
    private CaseRepository caseRepository;

    @Mock
    private CaseDocumentRepository caseDocumentRepository;

    @Mock
    private CaseStatusService caseStatusService;

    @Mock
    private ClaimsAnalysisClient claimsAnalysisClient;

    @Mock
    private ClassificationOutcomeService classificationOutcomeService;

    @Mock
    private InsurerRepository insurerRepository;

    private ClassificationRefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ClassificationRefreshScheduler(
                caseRepository, caseDocumentRepository, caseStatusService, claimsAnalysisClient,
                classificationOutcomeService, insurerRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        // The sweep is per tenant, so every test needs at least one insurer. Lenient because the
        // multi-tenant tests override this stub before it is ever called.
        lenient().when(insurerRepository.findByActiveTrue())
                .thenReturn(List.of(insurer(1L, "arbiter_bbva")));
        // By default the sweep wins the turn (the counter CAS updates 1 row); concurrency tests
        // override it with 0 to simulate another sweep getting there first.
        lenient().when(caseRepository.advanceClassificationAttempts(anyLong(), anyInt(), anyInt()))
                .thenReturn(1);
        lenient().when(caseDocumentRepository.findByCaseId(any())).thenReturn(List.of());
        // Same for the recovery sweep's CAS.
        lenient().when(caseRepository.claimFailedCaseForRequeue(anyLong(), any()))
                .thenReturn(1);
        lenient().when(claimsAnalysisClient.isReachable()).thenReturn(true);
        setField("giveUpAfter", Duration.ofHours(3));
        setField("requeueCooldown", Duration.ofMinutes(30));
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
        waitingSince(entity, Duration.ofHours(1));
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenReturn(false);

        scheduler.refreshPendingCases();

        // A conditional update of the counter, NOT save() of the whole entity: saving would rewrite
        // the row from a stale copy and revert concurrent changes.
        verify(caseRepository).advanceClassificationAttempts(entity.getId(), 0, 1);
        verify(caseRepository, never()).save(any());
        verify(classificationOutcomeService, never()).markFailed(any(), any());
    }

    @Test
    void waitedTheWholeWindow_marksClassificationFailed() {
        Case entity = pendingCase(17);
        waitingSince(entity, Duration.ofHours(3));
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenReturn(false);

        scheduler.refreshPendingCases();

        verify(caseRepository).advanceClassificationAttempts(entity.getId(), 17, 18);
        verify(classificationOutcomeService).markFailed(entity,
                "clasificación fallida: sin resultado después de 3 h");
        verify(caseRepository, never()).save(any());
    }

    @Test
    void manyAttemptsWithinTheWindow_keepWaiting() {
        Case entity = pendingCase(539);
        waitingSince(entity, Duration.ofMinutes(179));
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenReturn(false);

        scheduler.refreshPendingCases();

        verify(classificationOutcomeService, never()).markFailed(any(), any());
    }

    /**
     * More than one sweep may run against the same cases. The one that loses the turn (the counter
     * CAS updates no row) must not mark anything as failed, or case_status_history gets duplicates.
     */
    @Test
    void anotherSweepAlreadyAdvancedTheCase_doesNotTransitionAgain() {
        Case entity = pendingCase(2);
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenReturn(false);
        when(caseRepository.advanceClassificationAttempts(entity.getId(), 2, 3)).thenReturn(0);

        scheduler.refreshPendingCases();

        verify(classificationOutcomeService, never()).markFailed(any(), any());
        verify(caseStatusService, never()).enteredCurrentStatusAt(any());
    }

    @Test
    void exceptionDuringRefresh_incrementsAttempts() {
        Case entity = pendingCase(0);
        waitingSince(entity, Duration.ofMinutes(10));
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenThrow(new RuntimeException("connection refused"));

        scheduler.refreshPendingCases();

        verify(caseRepository).advanceClassificationAttempts(entity.getId(), 0, 1);
        verify(caseRepository, never()).save(any());
    }

    @Test
    void exceptionOnceTheWindowRanOut_marksClassificationFailed() {
        Case entity = pendingCase(2);
        waitingSince(entity, Duration.ofHours(4));
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenThrow(new RuntimeException("timeout"));

        scheduler.refreshPendingCases();

        verify(caseRepository).advanceClassificationAttempts(entity.getId(), 2, 3);
        verify(classificationOutcomeService).markFailed(eq(entity), any());
    }

    @Test
    void giveUpWindowNotInWholeHours_isDescribedInMinutes() {
        setField("giveUpAfter", Duration.ofMinutes(90));
        Case entity = pendingCase(2);
        waitingSince(entity, Duration.ofMinutes(90));
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenReturn(false);

        scheduler.refreshPendingCases();

        verify(classificationOutcomeService).markFailed(entity,
                "clasificación fallida: sin resultado después de 90 min");
    }

    @Test
    void multiplePendingCases_processesEachIndependently() {
        Case resolved = pendingCase(0);
        Case unresolved = pendingCase(1);
        Case failing = pendingCase(2);
        waitingSince(unresolved, Duration.ofMinutes(30));
        waitingSince(failing, Duration.ofHours(5));

        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION))
                .thenReturn(List.of(resolved, unresolved, failing));
        when(claimsAnalysisClient.refreshClassification(resolved)).thenReturn(true);
        when(claimsAnalysisClient.refreshClassification(unresolved)).thenReturn(false);
        when(claimsAnalysisClient.refreshClassification(failing)).thenReturn(false);

        scheduler.refreshPendingCases();

        assertThat(resolved.getClassificationAttempts()).isEqualTo(0);
        verify(caseRepository).advanceClassificationAttempts(unresolved.getId(), 1, 2);
        verify(caseRepository).advanceClassificationAttempts(failing.getId(), 2, 3);
        verify(caseRepository, never()).save(any());
        verify(classificationOutcomeService).markFailed(eq(failing), any());
        verify(classificationOutcomeService, never()).markFailed(eq(unresolved), any());
    }

    @Test
    void sweepsEveryActiveInsurer_withThatTenantResolvedEachTime() {
        when(insurerRepository.findByActiveTrue())
                .thenReturn(List.of(insurer(1L, "arbiter_bbva"), insurer(2L, "arbiter_provincia")));
        // Capture the tenant in effect when each schema is queried: proving the sweep switches
        // tenants is the point of this test.
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

    @Test
    void recoverInfrastructureFailures_noFailedCases_doesNothing() {
        when(caseRepository.findFailedByReason(ClassificationFailureReason.INFRASTRUCTURE))
                .thenReturn(List.of());

        scheduler.recoverInfrastructureFailures();

        verifyNoInteractions(claimsAnalysisClient);
    }

    @Test
    void recoverInfrastructureFailures_infrastructureFailure_requeuesAndRetriggersClassification() {
        Case entity = failedCase(3, ClassificationFailureReason.INFRASTRUCTURE);
        entity.setRiskScore(0.8);
        entity.setRulesClassification(Classification.FAST_TRACK);
        when(caseRepository.findFailedByReason(ClassificationFailureReason.INFRASTRUCTURE))
                .thenReturn(List.of(entity));
        when(caseRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        scheduler.recoverInfrastructureFailures();

        // The CAS is the gate: the turn is taken before touching anything.
        verify(caseRepository).claimFailedCaseForRequeue(
                entity.getId(), ClassificationFailureReason.INFRASTRUCTURE);
        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.PENDING_CLASSIFICATION),
                eq(StatusChangeActor.SYSTEM), any());
        assertThat(entity.getClassificationAttempts()).isEqualTo(0);
        assertThat(entity.getRiskScore()).isNull();
        assertThat(entity.getRulesClassification()).isNull();
        verify(claimsAnalysisClient).analyzeAndPersistAsSystem(entity, List.of());
    }

    @Test
    void recoverInfrastructureFailures_classificationStillUnreachable_leavesTheCasesForTheNextSweep() {
        Case entity = failedCase(3, ClassificationFailureReason.INFRASTRUCTURE);
        when(caseRepository.findFailedByReason(ClassificationFailureReason.INFRASTRUCTURE))
                .thenReturn(List.of(entity));
        when(claimsAnalysisClient.isReachable()).thenReturn(false);

        scheduler.recoverInfrastructureFailures();

        verify(caseRepository, never()).claimFailedCaseForRequeue(anyLong(), any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
        verify(claimsAnalysisClient, never()).analyzeAndPersistAsSystem(any(), any());
    }

    @Test
    void recoverInfrastructureFailures_requeuedAutomaticallyWithinTheCooldown_waits() {
        Case entity = failedCase(3, ClassificationFailureReason.INFRASTRUCTURE);
        when(caseRepository.findFailedByReason(ClassificationFailureReason.INFRASTRUCTURE))
                .thenReturn(List.of(entity));
        lastAutomaticRequeue(entity, Duration.ofMinutes(29));

        scheduler.recoverInfrastructureFailures();

        verify(claimsAnalysisClient, never()).isReachable();
        verify(caseRepository, never()).claimFailedCaseForRequeue(anyLong(), any());
        verify(claimsAnalysisClient, never()).analyzeAndPersistAsSystem(any(), any());
    }

    @Test
    void recoverInfrastructureFailures_lastAutomaticRequeueOlderThanTheCooldown_requeues() {
        Case entity = failedCase(3, ClassificationFailureReason.INFRASTRUCTURE);
        when(caseRepository.findFailedByReason(ClassificationFailureReason.INFRASTRUCTURE))
                .thenReturn(List.of(entity));
        when(caseRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        lastAutomaticRequeue(entity, Duration.ofMinutes(30));

        scheduler.recoverInfrastructureFailures();

        verify(claimsAnalysisClient).analyzeAndPersistAsSystem(entity, List.of());
    }

    @Test
    void recoverInfrastructureFailures_onlyTheCasesOutOfTheCooldownAreRequeued() {
        Case cooling = failedCase(3, ClassificationFailureReason.INFRASTRUCTURE);
        Case ready = failedCase(4, ClassificationFailureReason.INFRASTRUCTURE);
        when(caseRepository.findFailedByReason(ClassificationFailureReason.INFRASTRUCTURE))
                .thenReturn(List.of(cooling, ready));
        when(caseRepository.findById(ready.getId())).thenReturn(Optional.of(ready));
        lastAutomaticRequeue(cooling, Duration.ofMinutes(5));

        scheduler.recoverInfrastructureFailures();

        verify(claimsAnalysisClient).analyzeAndPersistAsSystem(ready, List.of());
        verify(claimsAnalysisClient, never()).analyzeAndPersistAsSystem(eq(cooling), any());
    }

    /**
     * Same as {@link #anotherSweepAlreadyAdvancedTheCase_doesNotTransitionAgain} for the recovery
     * sweep: the one that loses the CAS must not requeue, or two classifications go out.
     */
    @Test
    void recoverInfrastructureFailures_anotherSweepAlreadyClaimedTheCase_doesNotRequeueAgain() {
        Case entity = failedCase(3, ClassificationFailureReason.INFRASTRUCTURE);
        when(caseRepository.findFailedByReason(ClassificationFailureReason.INFRASTRUCTURE))
                .thenReturn(List.of(entity));
        when(caseRepository.claimFailedCaseForRequeue(
                entity.getId(), ClassificationFailureReason.INFRASTRUCTURE)).thenReturn(0);

        scheduler.recoverInfrastructureFailures();

        verify(caseStatusService, never()).transition(any(), any(), any(), any());
        verify(claimsAnalysisClient, never()).analyzeAndPersistAsSystem(any(), any());
        // Not even re-read: losing the turn stops before touching the database again.
        verify(caseRepository, never()).findById(any());
    }

    /**
     * An analyst may have manually moved the case out of {@code CLASSIFICATION_FAILED} after the
     * list was built. The CAS checks the failure reason, not the status, so the re-read must stop it.
     */
    @Test
    void recoverInfrastructureFailures_caseNoLongerEligibleByTheTimeItsReRead_isSkipped() {
        Case entity = failedCase(3, ClassificationFailureReason.INFRASTRUCTURE);
        when(caseRepository.findFailedByReason(ClassificationFailureReason.INFRASTRUCTURE))
                .thenReturn(List.of(entity));

        Case movedOn = failedCase(3, ClassificationFailureReason.INFRASTRUCTURE);
        movedOn.setCurrentStatus(CaseStates.of(CaseStatus.PENDING_CLASSIFICATION));
        when(caseRepository.findById(entity.getId())).thenReturn(Optional.of(movedOn));

        scheduler.recoverInfrastructureFailures();

        verify(caseStatusService, never()).transition(any(), any(), any(), any());
        verify(claimsAnalysisClient, never()).analyzeAndPersistAsSystem(any(), any());
    }

    @Test
    void recoverInfrastructureFailures_sweepsEveryActiveInsurer_withThatTenantResolvedEachTime() {
        when(insurerRepository.findByActiveTrue())
                .thenReturn(List.of(insurer(1L, "arbiter_bbva"), insurer(2L, "arbiter_provincia")));
        List<String> tenantsSeen = new ArrayList<>();
        when(caseRepository.findFailedByReason(ClassificationFailureReason.INFRASTRUCTURE))
                .thenAnswer(invocation -> {
                    tenantsSeen.add(TenantContext.get());
                    return List.of();
                });

        scheduler.recoverInfrastructureFailures();

        assertThat(tenantsSeen).containsExactly("arbiter_bbva", "arbiter_provincia");
        assertThat(TenantContext.get()).isEqualTo(TenantContext.COMMON_SCHEMA);
    }

    @Test
    void recoverInfrastructureFailures_oneInsurerFailing_doesNotStopTheRest() {
        when(insurerRepository.findByActiveTrue())
                .thenReturn(List.of(insurer(1L, "arbiter_bbva"), insurer(2L, "arbiter_provincia")));
        Case entity = failedCase(3, ClassificationFailureReason.INFRASTRUCTURE);
        when(caseRepository.findFailedByReason(ClassificationFailureReason.INFRASTRUCTURE))
                .thenThrow(new RuntimeException("schema unreachable"))
                .thenReturn(List.of(entity));
        when(caseRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        scheduler.recoverInfrastructureFailures();

        verify(claimsAnalysisClient).analyzeAndPersistAsSystem(entity, List.of());
        assertThat(TenantContext.get()).isEqualTo(TenantContext.COMMON_SCHEMA);
    }

    private void waitingSince(Case entity, Duration waited) {
        when(caseStatusService.enteredCurrentStatusAt(entity)).thenReturn(NOW.minus(waited));
    }

    private void lastAutomaticRequeue(Case entity, Duration ago) {
        when(caseStatusService.lastTransitionAt(entity.getId(), CaseStatus.CLASSIFICATION_FAILED,
                CaseStatus.PENDING_CLASSIFICATION, StatusChangeActor.SYSTEM))
                .thenReturn(Optional.of(NOW.minus(ago)));
    }

    private Case failedCase(long id, ClassificationFailureReason reason) {
        return Case.builder()
                .id(id)
                .claimCause(CaseFixtures.claimCause("Celulares", "Robo en vía pública"))
                .declaredItem("Motorola Edge 50 Pro")
                .insured(CaseFixtures.insured("40.123.456", "Laura", "Fernández"))
                .policy(CaseFixtures.policy("POL-CEL-2024-001", "Celular Protegido Básico"))
                .coverage(CaseFixtures.coverage("Celulares"))
                .description("Test case")
                .occurredAt(LocalDateTime.of(2026, 6, 13, 19, 45))
                .eventAddress("CABA")
                .claimedAmount(new BigDecimal("150000"))
                .currentStatus(CaseStates.of(CaseStatus.CLASSIFICATION_FAILED))
                .classificationAttempts(540)
                .classificationFailureReason(reason)
                .build();
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

    private void setField(String name, Object value) {
        try {
            var field = ClassificationRefreshScheduler.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(scheduler, value);
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
