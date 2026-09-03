package ar.edu.utn.frba.arbiter.cases.services;

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
import java.util.Set;

import static ar.edu.utn.frba.arbiter.common.enums.CaseStatus.*;

/**
 * Single entry point for every case status change. Both mutating the case and appending the
 * audit row happen here, so a case can't move states without leaving a trail
 * (Expediente = estado + trazabilidad). Nothing else should call {@code Case.setCurrentStatus}.
 */
@Service
@RequiredArgsConstructor
public class CaseStatusService {

    /**
     * Ley 17.418 art. 56: the insurer has 30 days from when the term is actually running to
     * pronounce itself, and staying silent means acceptance. A constant and not a rule in
     * rules-service because it isn't the insurer's to configure — the law sets it, the same for
     * every tenant. Owned here (not {@code CaseServiceImpl}) because this service is also what
     * resets it when the term resumes — see {@link #transition}.
     */
    public static final int RESPONSE_TERM_DAYS = 30;

    /**
     * States where the case is waiting on a third party (the insured, or an external expert) —
     * the "requerimiento" the doc de dominio BBVA (§1, §8) describes as what interrupts the art.
     * 56 term. While a case sits in one of these, {@link CaseServiceImpl#responseDeadline} is
     * frozen and not counted as due (see {@code DeadlineSweepScheduler},
     * {@code CaseSpecifications.dueSoonBefore}).
     */
    public static final Set<CaseStatus> PAUSING_STATUSES = Set.of(AWAITING_DOCUMENTATION, PENDING_EXPERT_REPORT);

    /**
     * States where the case is closed ({@code case_status.is_final = TRUE}). The art. 56 term is
     * over in all three: two because the insurer pronounced itself, {@code LAPSED} because the
     * insured never came back. The only way out is a reapertura ({@code CaseServiceImpl.reopenCase}).
     */
    public static final Set<CaseStatus> TERMINAL_STATUSES = Set.of(APPROVED, REJECTED, LAPSED);

    /**
     * Whether the art. 56 clock is ticking in this status — the single definition the deadline
     * semaphore, the sweep and the term reset all answer to. It is <b>not</b> running while the
     * case waits on a third party ({@link #PAUSING_STATUSES}: the term is interrupted and the
     * stored {@code responseDeadline} is a frozen date) nor once it is closed
     * ({@link #TERMINAL_STATUSES}).
     *
     * <p>An unresolvable status counts as running: the safe default is to keep watching a deadline
     * we can't classify, not to silently stop.
     */
    public static boolean isDeadlineRunning(CaseStatus status) {
        return status == null
                || (!PAUSING_STATUSES.contains(status) && !TERMINAL_STATUSES.contains(status));
    }

    private static final Map<CaseStatus, Set<CaseStatus>> VALID_TRANSITIONS = Map.of(
            PENDING_CLASSIFICATION,  Set.of(PENDING_ANALYST_REVIEW, AWAITING_DOCUMENTATION, CLASSIFICATION_FAILED),
            // LAPSED: LapseSweepScheduler closes a case that sat here 18 months from the denuncia
            // with no movement from the insured — "inacción del asegurado ante requerimientos".
            AWAITING_DOCUMENTATION,  Set.of(PENDING_CLASSIFICATION, LAPSED),
            PENDING_ANALYST_REVIEW,  Set.of(APPROVED, REJECTED, PENDING_CLASSIFICATION, PENDING_EXPERT_REPORT),
            CLASSIFICATION_FAILED,   Set.of(PENDING_CLASSIFICATION),
            // Back to the analyst and nowhere else. A derived case can't be approved or rejected
            // without its report — that is the whole point of having derived it — and it can't be
            // derived twice, because there is no way out of here except through review.
            PENDING_EXPERT_REPORT,   Set.of(PENDING_ANALYST_REVIEW),
            // Reapertura ("rehabilitación" in the doc de dominio BBVA): the three terminal states
            // lead back to the analyst's desk and nowhere else. Reopening is not a new verdict —
            // it only puts the case in front of a human again, so it lands in the one state that
            // means exactly that. See CaseServiceImpl.reopenCase.
            APPROVED,                Set.of(PENDING_ANALYST_REVIEW),
            REJECTED,                Set.of(PENDING_ANALYST_REVIEW),
            LAPSED,                  Set.of(PENDING_ANALYST_REVIEW)
    );

    private final CaseRepository caseRepository;
    private final CaseStatusHistoryRepository historyRepository;
    private final CaseStateCatalog caseStateCatalog;
    private final CaseNotificationService notificationService;
    private final Clock clock;

    /**
     * The state every case is born in. Lives here rather than in {@code CaseServiceImpl} so the
     * catalog stays behind this service — the entry point for anything status-shaped.
     */
    public CaseState initialStatus() {
        return caseStateCatalog.resolve(PENDING_CLASSIFICATION);
    }

    /** Records the case's birth (null → its initial status). The case is already persisted. */
    @Transactional
    public void recordCreation(Case caseRecord, StatusChangeActor actor, String reason) {
        appendHistory(caseRecord.getId(), null, caseRecord.getCurrentStatus(), actor, reason);
        // "We got your claim" belongs to the birth and not to transition(): a case comes back to
        // PENDING_CLASSIFICATION every time the insured uploads what was missing, and notifying
        // there would greet them again on every upload.
        notificationService.notifyStatusChange(caseRecord, PENDING_CLASSIFICATION);
    }

    /**
     * Moves the case to a new status, records the transition, and persists the case.
     *
     * <p>{@code @Transactional}: called from {@code ClassificationRefreshScheduler} (no HTTP
     * request behind it, so no open-in-view or any other ambient session/transaction to piggyback
     * on) — without this, the history save and the case save each needed their own implicit
     * transaction to reliably flush, and in that codepath specifically one of them was failing
     * with "No EntityManager with actual transaction available ... cannot reliably process
     * 'flush' call". This also makes the two writes atomic, which they always should've been.
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

        // After persisting, never before: notifying about a move that then fails to save would tell
        // the insured something that didn't happen. PENDING_CLASSIFICATION is excluded here — see
        // recordCreation().
        if (isReopening(from, to)) {
            // A reopening can't be told apart by its destination — it lands in the same status an
            // ordinary classification does — so it needs the pair. Resolved here and not by the
            // caller so any future path that reopens a case notifies too, without remembering to.
            notificationService.notifyReopened(saved);
        } else if (to != PENDING_CLASSIFICATION) {
            notificationService.notifyStatusChange(saved, to);
        }
        return saved;
    }

    /**
     * A case coming back out of a closed state — the reapertura ("rehabilitación") of the doc de
     * dominio BBVA. The destination alone doesn't identify it: an ordinary classification reaches
     * {@code PENDING_ANALYST_REVIEW} too, and the insured must not be told "reabrimos tu siniestro"
     * every time the model finishes.
     */
    private static boolean isReopening(CaseStatus from, CaseStatus to) {
        return to == PENDING_ANALYST_REVIEW && TERMINAL_STATUSES.contains(from);
    }

    /**
     * Appends an ownership change (assign / reassign / release) to the same trail as the status
     * moves, so "quién lo tomó y cuándo" is auditable next to "qué pasó con el expediente".
     *
     * <p>Assigning is not a state machine move: the case keeps its status, which is why this
     * bypasses {@link #transition} instead of trying to squeeze through VALID_TRANSITIONS. The row
     * records {@code from == to} on purpose — that equality is the marker the frontend timeline
     * uses to render it as a milestone without a status arrow. Human-in-the-loop is untouched:
     * having an owner is not a decision (decisión de arquitectura #5).
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
     * The doc de dominio BBVA (§1, regla estructural clave; §8) models the art. 56 term as
     * something that <b>interrupts</b> while the aseguradora is waiting on a third party, and
     * runs again <b>in full</b> — not just the remaining days — once the requirement is <b>met</b>.
     * So any move from a status where the clock was stopped into one where it runs resets
     * {@code responseDeadline} to a fresh {@link #RESPONSE_TERM_DAYS} from today; the reverse move
     * freezes it in place (nothing to do here — the sweep/semaphore skip those statuses instead of
     * reading a stale date as urgency).
     *
     * <p>Expressed against {@link #isDeadlineRunning} rather than against
     * {@link #PAUSING_STATUSES} alone, so the two cases fall out of the same rule:
     * <ul>
     *   <li>the requirement was met (leaving a pausing status) — the term resumes in full;</li>
     *   <li>a terminal case is reopened — the insurer is on the hook again from scratch, which is
     *       the only reading that doesn't hand the analyst an already-overdue case the moment they
     *       correct their own mistake.</li>
     * </ul>
     * And the moves that must <b>not</b> reset are excluded for free, because both ends are
     * stopped: {@code AWAITING_DOCUMENTATION → LAPSED} (the requirement was never met, so there's
     * no term to resume) and {@code PENDING_ANALYST_REVIEW → APPROVED/REJECTED} (answered).
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
