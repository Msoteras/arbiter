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
import ar.edu.utn.frba.arbiter.common.enums.ClassificationFailureReason;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClassificationRefreshSchedulerTest {

    @Mock
    private CaseRepository caseRepository;

    @Mock
    private CaseDocumentRepository caseDocumentRepository;

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
                caseRepository, caseDocumentRepository, caseStatusService, claimsAnalysisClient, insurerRepository);
        // The sweep is per tenant now, so every test needs at least one insurer to sweep.
        // A single one keeps these cases testing what they always tested; the multi-tenant
        // behaviour has its own tests below. Lenient because those two override this stub
        // before it is ever called, which strict stubs would otherwise flag.
        lenient().when(insurerRepository.findByActiveTrue())
                .thenReturn(List.of(insurer(1L, "arbiter_bbva")));
        // Por default el barrido se queda con el turno (el CAS del contador devuelve 1 fila). Los
        // tests de concurrencia lo pisan con 0 para simular que otro llegó primero.
        lenient().when(caseRepository.advanceClassificationAttempts(anyLong(), anyInt(), anyInt()))
                .thenReturn(1);
        lenient().when(caseDocumentRepository.findByCaseId(any())).thenReturn(List.of());
        // Ídem para el CAS del barrido de recuperación: por default se queda con el turno. El test
        // de concurrencia lo pisa con 0.
        lenient().when(caseRepository.claimFailedCaseForRequeue(anyLong(), any()))
                .thenReturn(1);
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

        // Update puntual y condicional del contador, NO save() de la entidad entera: guardar la
        // entidad reescribía toda la fila desde una copia vieja y revertía cambios concurrentes (un
        // reintento del analista volvía solo a PENDING_CLASSIFICATION cada pocos segundos).
        verify(caseRepository).advanceClassificationAttempts(entity.getId(), 0, 1);
        verify(caseRepository, never()).save(any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void maxAttemptsReached_marksClassificationFailed() {
        Case entity = pendingCase(2);
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenReturn(false);
        when(caseRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        scheduler.refreshPendingCases();

        verify(caseRepository).advanceClassificationAttempts(entity.getId(), 2, 3);
        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.CLASSIFICATION_FAILED),
                eq(StatusChangeActor.SYSTEM), any());
        verify(caseRepository, never()).save(any());
    }

    /**
     * classification-service ya escribió el motivo estructurado en la misma fila
     * ({@code CaseOutcomeRepository.recordClassificationFailure}) antes de que el barrido se rinda
     * — esto verifica que la transición lo surface en el motivo en vez de dejar solo el genérico
     * "N reintentos".
     */
    @Test
    void maxAttemptsReached_withRecordedInfrastructureFailure_includesItInTransitionReason() {
        Case entity = pendingCase(2);
        entity.setClassificationFailureReason(ClassificationFailureReason.INFRASTRUCTURE);
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenReturn(false);
        when(caseRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        scheduler.refreshPendingCases();

        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.CLASSIFICATION_FAILED),
                eq(StatusChangeActor.SYSTEM), reasonCaptor.capture());
        assertThat(reasonCaptor.getValue()).contains("infrastructure");
    }

    /**
     * La regresión de las transiciones duplicadas: la base de Railway es compartida, así que hay
     * más de un barrido corriendo contra los mismos expedientes. El que no se queda con el turno
     * (el CAS del contador no actualiza ninguna fila) no puede marcar nada como fallido — si no,
     * quedan dos filas idénticas en case_status_history.
     */
    @Test
    void anotherSweepAlreadyAdvancedTheCase_doesNotTransitionAgain() {
        Case entity = pendingCase(2);
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenReturn(false);
        when(caseRepository.advanceClassificationAttempts(entity.getId(), 2, 3)).thenReturn(0);

        scheduler.refreshPendingCases();

        verify(caseStatusService, never()).transition(any(), any(), any(), any());
        verify(caseRepository, never()).findById(any());
    }

    /**
     * Entre que el barrido leyó el expediente y se le agotaron los intentos pueden pasar minutos.
     * Si en el medio salió de PENDING_CLASSIFICATION (lo reintentó un analista, o llegó el
     * resultado), marcarlo fallido con la copia vieja escribiría una transición que no corresponde.
     */
    @Test
    void caseLeftPendingBeforeGivingUp_doesNotTransition() {
        Case entity = pendingCase(2);
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenReturn(false);

        Case reclassified = pendingCase(2);
        reclassified.setCurrentStatus(CaseStates.of(CaseStatus.PENDING_ANALYST_REVIEW));
        when(caseRepository.findById(entity.getId())).thenReturn(Optional.of(reclassified));

        scheduler.refreshPendingCases();

        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void exceptionDuringRefresh_incrementsAttempts() {
        Case entity = pendingCase(0);
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenThrow(new RuntimeException("connection refused"));

        scheduler.refreshPendingCases();

        verify(caseRepository).advanceClassificationAttempts(entity.getId(), 0, 1);
        verify(caseRepository, never()).save(any());
    }

    @Test
    void exceptionAtMaxAttempts_marksClassificationFailed() {
        Case entity = pendingCase(2);
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of(entity));
        when(claimsAnalysisClient.refreshClassification(entity)).thenThrow(new RuntimeException("timeout"));
        when(caseRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        scheduler.refreshPendingCases();

        verify(caseRepository).advanceClassificationAttempts(entity.getId(), 2, 3);
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
        when(caseRepository.findById(failing.getId())).thenReturn(Optional.of(failing));

        scheduler.refreshPendingCases();

        assertThat(resolved.getClassificationAttempts()).isEqualTo(0);
        assertThat(unresolved.getStatus()).isEqualTo(CaseStatus.PENDING_CLASSIFICATION);

        // Solo al que sigue pendiente se le sube el contador; el que agotó los intentos pasa por
        // la transición de estado. El contador ya no se persiste con save() — ver
        // unresolvedCase_incrementsAttempts.
        verify(caseRepository).advanceClassificationAttempts(unresolved.getId(), 1, 2);
        verify(caseRepository, never()).save(any());
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

    // ─── recoverInfrastructureFailures() — the second, slower sweep ─────────────────────────

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
        entity.setDeterministicFastTrack(true);
        when(caseRepository.findFailedByReason(ClassificationFailureReason.INFRASTRUCTURE))
                .thenReturn(List.of(entity));
        when(caseRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        scheduler.recoverInfrastructureFailures();

        // El CAS es la puerta: se toma el turno antes de tocar nada.
        verify(caseRepository).claimFailedCaseForRequeue(
                entity.getId(), ClassificationFailureReason.INFRASTRUCTURE);
        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.PENDING_CLASSIFICATION),
                eq(StatusChangeActor.SYSTEM), any());
        assertThat(entity.getClassificationAttempts()).isEqualTo(0);
        assertThat(entity.getRiskScore()).isNull();
        assertThat(entity.getDeterministicFastTrack()).isFalse();
        verify(claimsAnalysisClient).analyzeAndPersistAsSystem(entity, List.of());
    }

    /**
     * La misma regresión que {@link #anotherSweepAlreadyAdvancedTheCase_doesNotTransitionAgain}
     * cubre para el otro barrido, acá: con la instancia desplegada corriendo al lado de los stacks
     * locales del equipo, dos barridos leen el mismo CLASSIFICATION_FAILED. El que no se queda con
     * el turno (el CAS no limpia ninguna fila) no puede reencolar — si no, salen dos
     * clasificaciones y dos filas en case_status_history.
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
        verifyNoInteractions(claimsAnalysisClient);
        // Ni siquiera se relee: perder el turno corta antes de tocar la base de nuevo.
        verify(caseRepository, never()).findById(any());
    }

    /**
     * Un analista puede haber sacado el expediente de {@code CLASSIFICATION_FAILED} con el botón
     * manual entre que este barrido armó la lista y le tocó el turno. El CAS mira el motivo, no el
     * estado, así que la relectura posterior es la que tiene que frenarlo.
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
        verifyNoInteractions(claimsAnalysisClient);
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
