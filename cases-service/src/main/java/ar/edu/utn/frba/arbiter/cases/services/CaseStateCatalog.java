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
 * <p>Cached in memory: six rows, in the common schema (the same for every tenant, so one cache
 * serves them all) and effectively immutable at runtime. The cached instances are detached, which
 * is safe here because nothing writes through them — the association carries no cascade, so
 * Hibernate only ever reads the id off them to fill {@code current_status_id}. Reading
 * {@code getName()} on a detached instance also keeps {@code Case.getStatus()} working outside a
 * session, which a lazy proxy would not.
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
