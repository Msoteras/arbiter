package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidStatusTransitionException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseStatusHistory;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseStatusHistoryRepository;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static ar.edu.utn.frba.arbiter.common.enums.CaseStatus.*;

/**
 * Single entry point for every case status change. Both mutating the case and appending the
 * audit row happen here, so a case can't move states without leaving a trail
 * (Expediente = estado + trazabilidad). Nothing else should call {@code Case.setStatus}.
 */
@Service
@RequiredArgsConstructor
public class CaseStatusService {

    private static final Map<CaseStatus, Set<CaseStatus>> VALID_TRANSITIONS = Map.of(
            PENDING_CLASSIFICATION,  Set.of(PENDING_ANALYST_REVIEW, AWAITING_DOCUMENTATION, CLASSIFICATION_FAILED),
            AWAITING_DOCUMENTATION,  Set.of(PENDING_CLASSIFICATION),
            PENDING_ANALYST_REVIEW,  Set.of(APPROVED, REJECTED, PENDING_CLASSIFICATION),
            CLASSIFICATION_FAILED,   Set.of(PENDING_CLASSIFICATION)
    );

    private final CaseRepository caseRepository;
    private final CaseStatusHistoryRepository historyRepository;

    /** Records the case's birth (null → its initial status). The case is already persisted. */
    public void recordCreation(Case caseRecord, StatusChangeActor actor, String reason) {
        appendHistory(caseRecord.getId(), null, caseRecord.getStatus(), actor, reason);
    }

    /** Moves the case to a new status, records the transition, and persists the case. */
    public Case transition(Case caseRecord, CaseStatus to, StatusChangeActor actor, String reason) {
        CaseStatus from = caseRecord.getStatus();
        Set<CaseStatus> allowed = VALID_TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new InvalidStatusTransitionException(from, to);
        }

        appendHistory(caseRecord.getId(), from, to, actor, reason);
        caseRecord.setStatus(to);
        return caseRepository.save(caseRecord);
    }

    public List<CaseStatusHistory> history(Long caseId) {
        return historyRepository.findByCaseIdOrderByChangedAtAsc(caseId);
    }

    private void appendHistory(Long caseId, CaseStatus from, CaseStatus to, StatusChangeActor actor, String reason) {
        historyRepository.save(CaseStatusHistory.builder()
                .caseId(caseId)
                .fromStatus(from)
                .toStatus(to)
                .actor(actor)
                .reason(reason)
                .build());
    }
}
