package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.dto.EngineBoardResponse;
import ar.edu.utn.frba.arbiter.cases.dto.EngineCaseResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.ClassificationStepRecord;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClassificationStepRepository;
import ar.edu.utn.frba.arbiter.common.dto.ClassificationStep;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The engine board: which cases the classification is working on, and on which step. Steps come
 * from classification-service one by one; each is stored, folded into the case's latest run and
 * pushed to the insurer's topic, so the board never has to poll.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EngineBoardService {

    private static final List<ClassificationStage> TERMINAL =
            List.of(ClassificationStage.FINISHED, ClassificationStage.FAILED);

    private final ClassificationStepRepository stepRepository;
    private final CaseRepository caseRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    @Transactional
    public void record(Long caseId, ClassificationStep step) {
        Case caseRecord = caseRepository.findById(caseId).orElseThrow(() -> new CaseNotFoundException(caseId));
        stepRepository.save(ClassificationStepRecord.builder()
                .caseId(caseId)
                .stage(step.stage())
                .detail(step.detail())
                .itemIndex(step.itemIndex())
                .itemCount(step.itemCount())
                .result(step.result())
                .occurredAt(step.occurredAt())
                .build());
        latestRun(stepRepository.findByCaseIdInOrderByOccurredAtAscIdAsc(List.of(caseId)))
                .ifPresent(run -> broadcast(toResponse(caseRecord, run)));
    }

    @Transactional(readOnly = true)
    public EngineBoardResponse board() {
        Instant now = clock.instant();
        Instant startOfDay = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();

        List<Case> pending = caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION);
        List<Long> finishedToday = stepRepository.findByStageInAndOccurredAtGreaterThanEqual(TERMINAL, startOfDay)
                .stream().map(ClassificationStepRecord::getCaseId).distinct().toList();

        Map<Long, Case> cases = pending.stream().collect(Collectors.toMap(Case::getId, Function.identity()));
        caseRepository.findAllById(finishedToday).forEach(c -> cases.putIfAbsent(c.getId(), c));
        Map<Long, List<ClassificationStepRecord>> stepsByCase = stepsOf(cases.keySet());

        List<Long> pendingIds = pending.stream().map(Case::getId).toList();
        List<EngineCaseResponse> active = pendingIds.stream()
                .map(id -> latestRun(stepsByCase.getOrDefault(id, List.of()))
                        .filter(run -> !run.current().getStage().terminal())
                        .map(run -> toResponse(cases.get(id), run)))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(EngineCaseResponse::queuedAt))
                .toList();

        // A case reclassified later today shows its latest run only.
        List<EngineCaseResponse> classified = finishedToday.stream()
                .filter(cases::containsKey)
                .map(id -> latestRun(stepsByCase.getOrDefault(id, List.of()))
                        .filter(run -> run.current().getStage().terminal()
                                && !run.current().getOccurredAt().isBefore(startOfDay))
                        .map(run -> toResponse(cases.get(id), run)))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(EngineCaseResponse::stageAt).reversed())
                .toList();

        return new EngineBoardResponse(active, classified, now, EngineTopic.of(TenantContext.get()));
    }

    private Map<Long, List<ClassificationStepRecord>> stepsOf(Collection<Long> caseIds) {
        if (caseIds.isEmpty()) {
            return Map.of();
        }
        return stepRepository.findByCaseIdInOrderByOccurredAtAscIdAsc(caseIds).stream()
                .collect(Collectors.groupingBy(ClassificationStepRecord::getCaseId));
    }

    /** Best-effort: the step is stored already, and a broker failure must not fail the report. */
    private void broadcast(EngineCaseResponse response) {
        try {
            messagingTemplate.convertAndSend(EngineTopic.of(TenantContext.get()), response);
        } catch (RuntimeException ex) {
            log.error("Could not push the engine step of case {}", response.caseId(), ex);
        }
    }

    /**
     * The run that starts at the last {@code QUEUED}. Steps of an earlier run that arrive late sort
     * before it by {@code occurredAt} and are ignored. A run whose {@code QUEUED} was lost starts
     * at the first step.
     */
    static Optional<Run> latestRun(List<ClassificationStepRecord> steps) {
        if (steps.isEmpty()) {
            return Optional.empty();
        }
        int start = 0;
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i).getStage() == ClassificationStage.QUEUED) {
                start = i;
                break;
            }
        }
        return Optional.of(new Run(steps.subList(start, steps.size())));
    }

    private static EngineCaseResponse toResponse(Case caseRecord, Run run) {
        ClassificationStepRecord current = run.current();
        return new EngineCaseResponse(
                caseRecord.getId(),
                caseRecord.getInsured() == null ? null : caseRecord.getInsured().fullName(),
                caseRecord.getClaimCause() == null ? null : caseRecord.getClaimCause().getName(),
                current.getStage(),
                current.getDetail(),
                current.getItemIndex(),
                current.getItemCount(),
                current.getResult(),
                run.queuedAt(),
                run.startedAt(),
                current.getOccurredAt());
    }

    record Run(List<ClassificationStepRecord> steps) {

        ClassificationStepRecord current() {
            return steps.getLast();
        }

        Instant queuedAt() {
            ClassificationStepRecord first = steps.getFirst();
            return first.getOccurredAt();
        }

        /** The first step a thread ran; null while it only waited. */
        Instant startedAt() {
            return steps.stream()
                    .filter(step -> step.getStage() != ClassificationStage.QUEUED)
                    .map(ClassificationStepRecord::getOccurredAt)
                    .findFirst()
                    .orElse(null);
        }
    }
}
