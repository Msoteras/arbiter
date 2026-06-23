package ar.edu.utn.frba.arbiter.expedientes.services;

import ar.edu.utn.frba.arbiter.expedientes.dto.ExpedienteRequest;
import ar.edu.utn.frba.arbiter.expedientes.dto.ExpedienteResponse;

public interface ExpedienteService {

    ExpedienteResponse createExpediente(ExpedienteRequest request);

    ExpedienteResponse getExpediente(Long expedienteId);
}
