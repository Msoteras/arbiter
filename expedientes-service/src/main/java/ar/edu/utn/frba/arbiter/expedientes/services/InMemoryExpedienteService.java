package ar.edu.utn.frba.arbiter.expedientes.services;

import ar.edu.utn.frba.arbiter.expedientes.dto.ExpedienteRequest;
import ar.edu.utn.frba.arbiter.expedientes.dto.ExpedienteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class InMemoryExpedienteService implements ExpedienteService {

    private final SiniestrosAnalysisClient siniestrosAnalysisClient;
    private final AtomicLong expedienteCounter = new AtomicLong();
    private final ConcurrentMap<Long, ExpedienteResponse> store = new ConcurrentHashMap<>();

    @Override
    public ExpedienteResponse createExpediente(ExpedienteRequest request) {
        AnalysisResult analysis = siniestrosAnalysisClient.analyze(request);
        long expedienteId = expedienteCounter.incrementAndGet();
        ExpedienteResponse response = new ExpedienteResponse(
                expedienteId,
                "PENDIENTE_REVISION_ANALISTA",
                request.policyNumber(),
                request.insuredId(),
                analysis.classification(),
                analysis.confidence(),
                analysis.detail()
        );
        store.put(expedienteId, response);
        return response;
    }

    @Override
    public ExpedienteResponse getExpediente(Long expedienteId) {
        ExpedienteResponse response = store.get(expedienteId);
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Expediente " + expedienteId + " not found");
        }
        return response;
    }
}
