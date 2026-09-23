package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidStatusTransitionException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseStatusHistory;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseStatusHistoryRepository;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.CaseState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ar.edu.utn.frba.arbiter.common.enums.CaseStatus.*;

/**
 * Single entry point for every case status change: the case and its audit row are written
 * together, so a case can't move without leaving a trail. Nothing else should call
 * {@code Case.setCurrentStatus}.
 */
@Service
@RequiredArgsConstructor
public class CaseStatusService {

    /**
     * Art. 56 of Ley 17.418: 30 days to respond once the term is running, silence meaning
     * acceptance. A constant rather than a rule because the law sets it for every insurer.
     */
    public static final int RESPONSE_TERM_DAYS = 30;

    /**
     * States where the case waits on a third party, which interrupts the art. 56 term. Defined on
     * the enum because reports-service needs the same list.
     */
    public static final Set<CaseStatus> PAUSING_STATUSES = Set.copyOf(CaseStatus.pausingTheTerm());

    /** Closed states; the only way out is reopening. */
    public static final Set<CaseStatus> TERMINAL_STATUSES = Set.of(APPROVED, REJECTED, LAPSED);

    /**
     * The single definition the deadline priority, the sweep and the term reset answer to. An
     * unknown status counts as running: better to keep watching a deadline than silently stop.
     */
    public static boolean isDeadlineRunning(CaseStatus status) {
        return status == null
                || (!PAUSING_STATUSES.contains(status) && !TERMINAL_STATUSES.contains(status));
    }

    private static final Map<CaseStatus, Set<CaseStatus>> VALID_TRANSITIONS = Map.of(
            PENDING_CLASSIFICATION,  Set.of(PENDING_ANALYST_REVIEW, AWAITING_DOCUMENTATION, CLASSIFICATION_FAILED),
            AWAITING_DOCUMENTATION,  Set.of(PENDING_CLASSIFICATION, LAPSED),
            PENDING_ANALYST_REVIEW,  Set.of(APPROVED, REJECTED, PENDING_CLASSIFICATION, PENDING_EXPERT_REPORT,
                    PENDING_REPAIR),
            CLASSIFICATION_FAILED,   Set.of(PENDING_CLASSIFICATION),
            // Back to the analyst only: a referred case can't be decided without its report.
            PENDING_EXPERT_REPORT,   Set.of(PENDING_ANALYST_REVIEW),
            // The repair shop reports, it doesn't decide.
            PENDING_REPAIR,          Set.of(PENDING_ANALYST_REVIEW),
            // Reopening is not a new verdict: it only puts the case in front of an analyst again.
            APPROVED,                Set.of(PENDING_ANALYST_REVIEW),
            REJECTED,                Set.of(PENDING_ANALYST_REVIEW),
            LAPSED,                  Set.of(PENDING_ANALYST_REVIEW)
    );

    private final CaseRepository caseRepository;
    private final CaseStatusHistoryRepository historyRepository;
    private final CaseStateCatalog caseStateCatalog;
    private final CaseNotificationService notificationService;
    private final Clock clock;

    public CaseState initialStatus() {
        return caseStateCatalog.resolve(PENDING_CLASSIFICATION);
    }

    /** The case must already be persisted. */
    @Transactional
    public void recordCreation(Case caseRecord, StatusChangeActor actor, String reason) {
        appendHistory(caseRecord.getId(), null, caseRecord.getCurrentStatus(), actor, reason);
        // Notified here rather than in transition(): a case returns to PENDING_CLASSIFICATION on
        // every document upload, and the insured must not be greeted again each time.
        notificationService.notifyStatusChange(caseRecord, PENDING_CLASSIFICATION);
    }

    /**
     * {@code @Transactional} because the schedulers call it with no ambient transaction, and the
     * history row and the case must be written atomically.
     */
    @Transactional
    public Case transition(Case caseRecord, CaseStatus to, StatusChangeActor actor, String reason) {
        CaseStatus from = caseRecord.getStatus();
        Set<CaseStatus> allowed = VALID_TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new InvalidStatusTransitionException(from, to);
        }

        CaseState target = caseStateCatalog.resolve(to);
        appendHistory(caseRecord.getId(), caseRecord.getCurrentStatus(), target, actor, reason);
        caseRecord.setCurrentStatus(target);
        resumeDeadlineIfInterrupted(caseRecord, from, to);
        Case saved = caseRepository.save(caseRecord);

        // Only after persisting, so the insured is never told about a move that failed to save.
        if (isReopening(from, to)) {
            notificationService.notifyReopened(saved);
        } else if (to != PENDING_CLASSIFICATION) {
            notificationService.notifyStatusChange(saved, to);
        }
        return saved;
    }

    /**
     * {@link #transition} for callers not running in the transaction that loaded the case, such as
     * the sweeps: their copy may be stale and several instances may sweep the same schema. The
     * move only happens if the DB still holds {@code expected}; whoever loses the race writes nothing.
     *
     * <p>Returns the entity re-read after the compare-and-set, so the caller can persist more on it
     * without rewriting the row from the stale copy.
     *
     * @return the moved case, or empty if someone else got there first
     */
    @Transactional
    public Optional<Case> transitionIfStillIn(Case caseRecord, CaseStatus expected, CaseStatus to,
                                              StatusChangeActor actor, String reason) {
        Set<CaseStatus> allowed = VALID_TRANSITIONS.getOrDefault(expected, Set.of());
        if (!allowed.contains(to)) {
            throw new InvalidStatusTransitionException(expected, to);
        }

        CaseState from = caseStateCatalog.resolve(expected);
        CaseState target = caseStateCatalog.resolve(to);
        if (caseRepository.claimStatusTransition(caseRecord.getId(), from, target) == 0) {
            return Optional.empty();
        }

        Case moved = caseRepository.findById(caseRecord.getId())
                .orElseThrow(() -> new CaseNotFoundException(caseRecord.getId()));
        appendHistory(moved.getId(), from, target, actor, reason);
        resumeDeadlineIfInterrupted(moved, expected, to);
        Case saved = caseRepository.save(moved);

        if (isReopening(expected, to)) {
            notificationService.notifyReopened(saved);
        } else if (to != PENDING_CLASSIFICATION) {
            notificationService.notifyStatusChange(saved, to);
        }
        return Optional.of(saved);
    }

    /**
     * The destination alone doesn't identify a reopening: an ordinary classification also reaches
     * {@code PENDING_ANALYST_REVIEW}.
     */
    private static boolean isReopening(CaseStatus from, CaseStatus to) {
        return to == PENDING_ANALYST_REVIEW && TERMINAL_STATUSES.contains(from);
    }

    /**
     * Ownership changes go on the same trail as status moves but bypass the state machine: the row
     * records {@code from == to}, which is the marker the frontend timeline uses to render it.
     */
    @Transactional
    public void recordAssignment(Case caseRecord, StatusChangeActor actor, String reason) {
        appendHistory(caseRecord.getId(), caseRecord.getCurrentStatus(), caseRecord.getCurrentStatus(),
                actor, reason);
    }

    public List<CaseStatusHistory> history(Long caseId) {
        return historyRepository.findByCaseIdOrderByChangedAtAsc(caseId);
    }

    /**
     * The art. 56 term restarts in full, not with the remaining days, whenever the case moves from
     * a stopped status to a running one: a requirement was met, or a closed case was reopened.
     * Moves between two stopped statuses (e.g. {@code AWAITING_DOCUMENTATION → LAPSED}) don't reset.
     */
    private void resumeDeadlineIfInterrupted(Case caseRecord, CaseStatus from, CaseStatus to) {
        if (!isDeadlineRunning(from) && isDeadlineRunning(to)) {
            caseRecord.setResponseDeadline(LocalDate.now(clock).plusDays(RESPONSE_TERM_DAYS));
        }
    }

    private void appendHistory(Long caseId, CaseState from, CaseState to, StatusChangeActor actor, String reason) {
        historyRepository.save(CaseStatusHistory.builder()
                .caseId(caseId)
                .initialStatus(from)
                .finalStatus(to)
                .actor(actor)
                .reason(reason)
                .build());
    }
}
