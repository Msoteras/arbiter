package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Placeholder in-memory store — needs to become real Postgres persistence
 * (a Case entity + repository, mirroring classification-service's Claim) before
 * this can survive a restart or run with more than one instance.
 */
@Service
@RequiredArgsConstructor
public class InMemoryCaseService implements CaseService {

    private final ClaimsAnalysisClient claimsAnalysisClient;
    private final AtomicLong caseCounter = new AtomicLong();
    private final ConcurrentMap<Long, CaseResponse> store = new ConcurrentHashMap<>();

    @Override
    public CaseResponse createCase(CaseRequest request) {
        AnalysisResult analysis = claimsAnalysisClient.analyze(request);
        long caseId = caseCounter.incrementAndGet();
        CaseResponse response = new CaseResponse(
                caseId,
                "PENDIENTE_REVISION_ANALISTA",
                request.policyNumber(),
                request.insuredId(),
                analysis.classification(),
                analysis.confidence(),
                analysis.detail()
        );
        store.put(caseId, response);
        return response;
    }

    @Override
    public CaseResponse getCase(Long caseId) {
        CaseResponse response = store.get(caseId);
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case " + caseId + " not found");
        }
        return response;
    }
}
