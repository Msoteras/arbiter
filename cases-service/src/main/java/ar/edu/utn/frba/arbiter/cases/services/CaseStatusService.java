package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseStatusHistory;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseStatusHistoryRepository;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Single entry point for every case status change. Both mutating the case and appending the
 * audit row happen here, so a case can't move states without leaving a trail
 * (Expediente = estado + trazabilidad). Nothing else should call {@code Case.setStatus}.
 */
@Service
@RequiredArgsConstructor
public class CaseStatusService {

    private final CaseRepository caseRepository;
    private final CaseStatusHistoryRepository historyRepository;

    /** Records the case's birth (null → its initial status). The case is already persisted. */
    public void recordCreation(Case caseRecord, StatusChangeActor actor, String reason) {
        appendHistory(caseRecord.getId(), null, caseRecord.getStatus(), actor, reason);
    }

    /** Moves the case to a new status, records the transition, and persists the case. */
    public Case transition(Case caseRecord, CaseStatus to, StatusChangeActor actor, String reason) {
        appendHistory(caseRecord.getId(), caseRecord.getStatus(), to, actor, reason);
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
