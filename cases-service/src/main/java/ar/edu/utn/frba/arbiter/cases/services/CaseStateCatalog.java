package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.exceptions.UnknownCaseStateException;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseStateRepository;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.CaseState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translates between the {@code CaseStatus} enum the state machine speaks and the
 * {@code arbiter_common.case_status} rows the FK points at.
 *
 * <p>Cached in memory: the rows live in the common schema, so one cache serves every tenant. The
 * cached instances are detached, which is safe because the association has no cascade — Hibernate
 * only reads their id — and it keeps {@code Case.getStatus()} working outside a session.
 */
@Service
@RequiredArgsConstructor
public class CaseStateCatalog {

    private final CaseStateRepository caseStateRepository;

    private final Map<CaseStatus, CaseState> cache = new ConcurrentHashMap<>();

    public CaseState resolve(CaseStatus status) {
        return cache.computeIfAbsent(status, key -> caseStateRepository.findByName(key.name())
                .orElseThrow(() -> new UnknownCaseStateException(key.name())));
    }
}
