package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.dto.EngineBoardResponse;
import ar.edu.utn.frba.arbiter.cases.dto.EngineCaseResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.ClassificationStepRecord;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClassificationStepRepository;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.common.dto.ClassificationStep;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EngineBoardServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T15:00:00Z");

    @Mock
    private ClassificationStepRepository stepRepository;
    @Mock
    private CaseRepository caseRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private final List<ClassificationStepRecord> stored = new ArrayList<>();
    private EngineBoardService service;

    @BeforeEach
    void setUp() {
        service = new EngineBoardService(stepRepository, caseRepository, messagingTemplate,
                Clock.fixed(NOW, ZoneOffset.UTC));
        TenantContext.set("arbiter_bbva");
        when(stepRepository.save(any())).thenAnswer(invocation -> {
            stored.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        when(stepRepository.findByCaseIdInOrderByOccurredAtAscIdAsc(anyCollection()))
                .thenAnswer(invocation -> stored.stream()
                        .filter(step -> invocation.<java.util.Collection<Long>>getArgument(0).contains(step.getCaseId()))
                        .toList());
        when(stepRepository.findByStageInAndOccurredAtGreaterThanEqual(anyCollection(), any()))
                .thenAnswer(invocation -> stored.stream()
                        .filter(step -> invocation.<java.util.Collection<ClassificationStage>>getArgument(0)
                                .contains(step.getStage()))
                        .filter(step -> !step.getOccurredAt().isBefore(invocation.getArgument(1)))
                        .toList());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void aStep_isStoredAndPushedToTheInsurersBoard() {
        givenCase(7L);

        service.record(7L, step(ClassificationStage.QUEUED, "14:59:00"));
        service.record(7L, new ClassificationStep(ClassificationStage.DOCUMENT, "invoice", 2, 3, null, at("14:59:10")));

        ArgumentCaptor<EngineCaseResponse> pushed = ArgumentCaptor.forClass(EngineCaseResponse.class);
        verify(messagingTemplate, org.mockito.Mockito.times(2))
                .convertAndSend(eq("/topic/engine.bbva"), pushed.capture());
        EngineCaseResponse last = pushed.getValue();
        assertThat(last.caseId()).isEqualTo(7L);
        assertThat(last.insured()).isEqualTo("Martina Soteras");
        assertThat(last.claimCause()).isEqualTo("Hurto");
        assertThat(last.stage()).isEqualTo(ClassificationStage.DOCUMENT);
        assertThat(last.detail()).isEqualTo("invoice");
        assertThat(last.itemIndex()).isEqualTo(2);
        assertThat(last.itemCount()).isEqualTo(3);
        assertThat(last.queuedAt()).isEqualTo(at("14:59:00"));
        assertThat(last.startedAt()).isEqualTo(at("14:59:10"));
    }

    @Test
    void aStepForACaseThatDoesNotExist_isNotStored() {
        when(caseRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.record(9L, step(ClassificationStage.QUEUED, "14:59:00")))
                .isInstanceOf(CaseNotFoundException.class);
        verify(stepRepository, never()).save(any());
    }

    @Test
    void theBoard_splitsQueuedAndRunningFromWhatFinishedToday() {
        Case waiting = givenCase(1L);
        Case running = givenCase(2L);
        Case done = givenCase(3L);
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION))
                .thenReturn(List.of(waiting, running));
        when(caseRepository.findAllById(anyCollection())).thenReturn(List.of(done));
        stored.add(record(1L, ClassificationStage.QUEUED, "14:58:00"));
        stored.add(record(2L, ClassificationStage.QUEUED, "14:50:00"));
        stored.add(record(2L, ClassificationStage.POLICY, "14:50:05"));
        stored.add(record(2L, ClassificationStage.MODEL, "14:59:30"));
        stored.add(record(3L, ClassificationStage.QUEUED, "14:00:00"));
        stored.add(record(3L, ClassificationStage.POLICY, "14:00:02"));
        stored.add(finished(3L, "14:00:40"));

        EngineBoardResponse board = service.board();

        assertThat(board.active()).extracting(EngineCaseResponse::caseId).containsExactly(2L, 1L);
        assertThat(board.active().get(0).stage()).isEqualTo(ClassificationStage.MODEL);
        assertThat(board.active().get(1).startedAt()).isNull();
        assertThat(board.classifiedToday()).singleElement().satisfies(classified -> {
            assertThat(classified.caseId()).isEqualTo(3L);
            assertThat(classified.result()).isEqualTo(Classification.LLM_RECOMIENDA_APROBAR);
            assertThat(classified.startedAt()).isEqualTo(at("14:00:02"));
            assertThat(classified.stageAt()).isEqualTo(at("14:00:40"));
        });
        assertThat(board.serverTime()).isEqualTo(NOW);
        assertThat(board.topic()).isEqualTo("/topic/engine.bbva");
    }

    /** A reclassification starts a new run: the board follows it, not the run that already ended. */
    @Test
    void aReclassifiedCase_showsItsLatestRun() {
        Case reclassified = givenCase(4L);
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION))
                .thenReturn(List.of(reclassified));
        when(caseRepository.findAllById(anyCollection())).thenReturn(List.of(reclassified));
        stored.add(record(4L, ClassificationStage.QUEUED, "10:00:00"));
        stored.add(record(4L, ClassificationStage.FAILED, "10:05:00"));
        stored.add(record(4L, ClassificationStage.QUEUED, "14:59:50"));

        EngineBoardResponse board = service.board();

        assertThat(board.active()).singleElement().satisfies(active -> {
            assertThat(active.stage()).isEqualTo(ClassificationStage.QUEUED);
            assertThat(active.queuedAt()).isEqualTo(at("14:59:50"));
        });
        assertThat(board.classifiedToday()).isEmpty();
    }

    @Test
    void whatFinishedBeforeToday_isLeftOut() {
        when(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).thenReturn(List.of());
        when(caseRepository.findAllById(anyCollection())).thenReturn(List.of());
        stored.add(record(5L, ClassificationStage.QUEUED, "2026-10-24T22:00:00Z"));
        stored.add(finished(5L, "2026-10-24T22:01:00Z"));

        assertThat(service.board().classifiedToday()).isEmpty();
    }

    private Case givenCase(Long id) {
        Case caseRecord = new Case();
        caseRecord.setId(id);
        caseRecord.setInsured(CaseFixtures.insured("40.123.456", "Martina", "Soteras"));
        caseRecord.setClaimCause(CaseFixtures.claimCause("Celulares", "Hurto"));
        when(caseRepository.findById(id)).thenReturn(Optional.of(caseRecord));
        return caseRecord;
    }

    private static ClassificationStep step(ClassificationStage stage, String time) {
        return new ClassificationStep(stage, null, null, null, null, at(time));
    }

    private static ClassificationStepRecord record(Long caseId, ClassificationStage stage, String time) {
        return ClassificationStepRecord.builder().caseId(caseId).stage(stage).occurredAt(at(time)).build();
    }

    private static ClassificationStepRecord finished(Long caseId, String time) {
        return ClassificationStepRecord.builder().caseId(caseId).stage(ClassificationStage.FINISHED)
                .result(Classification.LLM_RECOMIENDA_APROBAR).occurredAt(at(time)).build();
    }

    /** A bare time is today's, in UTC like the fixed clock. */
    private static Instant at(String time) {
        return Instant.parse(time.contains("T") ? time : "2026-10-25T" + time + "Z");
    }
}
