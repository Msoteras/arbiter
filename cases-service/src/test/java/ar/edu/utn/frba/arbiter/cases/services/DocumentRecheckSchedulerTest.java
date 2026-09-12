package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.cases.support.CaseStates;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentRecheckSchedulerTest {

    private static final Instant FILED = Instant.parse("2026-09-10T15:00:00Z");

    @Mock
    private CaseRepository caseRepository;

    @Mock
    private CaseDocumentRepository caseDocumentRepository;

    @Mock
    private CaseStatusService caseStatusService;

    @Mock
    private RulesServiceClient rulesServiceClient;

    @Mock
    private InsurerRepository insurerRepository;

    private DocumentRecheckScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DocumentRecheckScheduler(
                caseRepository, caseDocumentRepository, caseStatusService, rulesServiceClient, insurerRepository);
        lenient().when(insurerRepository.findByActiveTrue())
                .thenReturn(List.of(insurer(1L, "arbiter_bbva")));
        // By default the sweep gets the turn; the concurrency tests override it with 0.
        lenient().when(caseRepository.claimUnverifiedDocuments(anyLong())).thenReturn(1);
    }

    @Test
    void nothingUnverified_asksNobody() {
        when(caseRepository.findByDocumentsUnverifiedSinceIsNotNull()).thenReturn(List.of());

        scheduler.recheckUnverifiedCases();

        verifyNoInteractions(rulesServiceClient, caseStatusService);
    }

    @Test
    void everyMandatoryDocumentPresent_clearsTheMarkAndLeavesNoTrace() {
        Case entity = unverified(1L, CaseStatus.PENDING_CLASSIFICATION);
        List<CaseDocument> attached = List.of(document("police_report"));
        when(caseRepository.findByDocumentsUnverifiedSinceIsNotNull()).thenReturn(List.of(entity));
        when(rulesServiceClient.requiredDocumentTypes("Celulares", "Robo en vía pública"))
                .thenReturn(List.of("police_report"));
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(attached);

        scheduler.recheckUnverifiedCases();

        verify(caseRepository).claimUnverifiedDocuments(1L);
        verifyNoInteractions(caseStatusService);
    }

    @Test
    void aMandatoryDocumentMissing_movesToAwaitingDocumentationAndNeverRejects() {
        Case entity = unverified(1L, CaseStatus.PENDING_CLASSIFICATION);
        List<CaseDocument> attached = List.of(document("police_report"));
        when(caseRepository.findByDocumentsUnverifiedSinceIsNotNull()).thenReturn(List.of(entity));
        when(rulesServiceClient.requiredDocumentTypes(anyString(), anyString()))
                .thenReturn(List.of("police_report", "purchase_proof"));
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(attached);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        scheduler.recheckUnverifiedCases();

        // The same transition the engine makes, so the insured gets the same notice.
        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.AWAITING_DOCUMENTATION),
                eq(StatusChangeActor.SYSTEM),
                argThat(reason -> reason.contains("purchase_proof") && !reason.contains("police_report")));
        verify(caseStatusService, never()).transition(any(), eq(CaseStatus.REJECTED), any(), any());
    }

    /** The claim is the turn: the second run finds the mark gone and doesn't notify again. */
    @Test
    void runningTwice_transitionsOnce() {
        Case entity = unverified(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseRepository.findByDocumentsUnverifiedSinceIsNotNull()).thenReturn(List.of(entity));
        when(rulesServiceClient.requiredDocumentTypes(anyString(), anyString()))
                .thenReturn(List.of("police_report"));
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        when(caseRepository.claimUnverifiedDocuments(1L)).thenReturn(1, 0);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        scheduler.recheckUnverifiedCases();
        scheduler.recheckUnverifiedCases();

        verify(caseStatusService, times(1))
                .transition(any(), eq(CaseStatus.AWAITING_DOCUMENTATION), any(), any());
    }

    @Test
    void anotherSweepClaimedIt_doesNothing() {
        Case entity = unverified(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseRepository.findByDocumentsUnverifiedSinceIsNotNull()).thenReturn(List.of(entity));
        when(rulesServiceClient.requiredDocumentTypes(anyString(), anyString()))
                .thenReturn(List.of("police_report"));
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        when(caseRepository.claimUnverifiedDocuments(1L)).thenReturn(0);

        scheduler.recheckUnverifiedCases();

        verify(caseRepository, never()).findById(any());
        verifyNoInteractions(caseStatusService);
    }

    @Test
    void rulesStillDown_keepsTheMarksAndStopsAskingForThatTenant() {
        Case first = unverified(1L, CaseStatus.PENDING_CLASSIFICATION);
        Case second = unverified(2L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseRepository.findByDocumentsUnverifiedSinceIsNotNull()).thenReturn(List.of(first, second));
        when(rulesServiceClient.requiredDocumentTypes(anyString(), anyString())).thenReturn(null);

        scheduler.recheckUnverifiedCases();

        verify(rulesServiceClient, times(1)).requiredDocumentTypes(anyString(), anyString());
        verify(caseRepository, never()).claimUnverifiedDocuments(anyLong());
        verifyNoInteractions(caseStatusService);
    }

    /** Its classification couldn't have finished without reading the schedule. */
    @Test
    void alreadyClassified_clearsTheMarkWithoutAskingRules() {
        Case entity = unverified(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findByDocumentsUnverifiedSinceIsNotNull()).thenReturn(List.of(entity));

        scheduler.recheckUnverifiedCases();

        verify(caseRepository).claimUnverifiedDocuments(1L);
        verifyNoInteractions(rulesServiceClient, caseStatusService);
    }

    @Test
    void alreadyAwaitingDocumentation_clearsTheMarkWithoutANewNotice() {
        Case entity = unverified(1L, CaseStatus.AWAITING_DOCUMENTATION);
        when(caseRepository.findByDocumentsUnverifiedSinceIsNotNull()).thenReturn(List.of(entity));

        scheduler.recheckUnverifiedCases();

        verify(caseRepository).claimUnverifiedDocuments(1L);
        verifyNoInteractions(rulesServiceClient, caseStatusService);
    }

    /** The recovery sweep requeues it; the recheck picks it up once it's PENDING_CLASSIFICATION. */
    @Test
    void failedClassification_keepsItsMark() {
        Case entity = unverified(1L, CaseStatus.CLASSIFICATION_FAILED);
        when(caseRepository.findByDocumentsUnverifiedSinceIsNotNull()).thenReturn(List.of(entity));

        scheduler.recheckUnverifiedCases();

        verify(caseRepository, never()).claimUnverifiedDocuments(anyLong());
        verifyNoInteractions(rulesServiceClient, caseStatusService);
    }

    @Test
    void pastTheAnalyst_isNotReopened() {
        Case entity = unverified(1L, CaseStatus.APPROVED);
        when(caseRepository.findByDocumentsUnverifiedSinceIsNotNull()).thenReturn(List.of(entity));

        scheduler.recheckUnverifiedCases();

        verify(caseRepository).claimUnverifiedDocuments(1L);
        verifyNoInteractions(rulesServiceClient, caseStatusService);
    }

    @Test
    void classifiedBetweenTheReadAndTheClaim_isLeftToItsClassification() {
        Case entity = unverified(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseRepository.findByDocumentsUnverifiedSinceIsNotNull()).thenReturn(List.of(entity));
        when(rulesServiceClient.requiredDocumentTypes(anyString(), anyString()))
                .thenReturn(List.of("police_report"));
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        when(caseRepository.findById(1L))
                .thenReturn(Optional.of(unverified(1L, CaseStatus.PENDING_ANALYST_REVIEW)));

        scheduler.recheckUnverifiedCases();

        verifyNoInteractions(caseStatusService);
    }

    @Test
    void oneInsurerFailing_doesNotStopTheOthers() {
        Case entity = unverified(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(insurerRepository.findByActiveTrue())
                .thenReturn(List.of(insurer(1L, "arbiter_bbva"), insurer(2L, "arbiter_provincia")));
        when(caseRepository.findByDocumentsUnverifiedSinceIsNotNull())
                .thenThrow(new RuntimeException("database unavailable"))
                .thenReturn(List.of(entity));
        when(rulesServiceClient.requiredDocumentTypes(anyString(), anyString())).thenReturn(List.of());
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());

        scheduler.recheckUnverifiedCases();

        verify(caseRepository).claimUnverifiedDocuments(1L);
    }

    private Case unverified(Long id, CaseStatus status) {
        return Case.builder()
                .id(id)
                .claimCause(CaseFixtures.claimCause("Celulares", "Robo en vía pública"))
                .declaredItem("Samsung A56")
                .insured(CaseFixtures.insured("40.123.456", "Laura", "Fernández"))
                .policy(CaseFixtures.policy("POL-CEL-2024-001", "Celular Protegido Básico"))
                .coverage(CaseFixtures.coverage("Celulares"))
                .description("Test case")
                .occurredAt(LocalDateTime.of(2026, 9, 9, 19, 45))
                .eventAddress("CABA")
                .currentStatus(CaseStates.of(status))
                .documentsUnverifiedSince(FILED)
                .build();
    }

    private CaseDocument document(String type) {
        CaseDocument document = mock(CaseDocument.class);
        when(document.getType()).thenReturn(type);
        return document;
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
}
