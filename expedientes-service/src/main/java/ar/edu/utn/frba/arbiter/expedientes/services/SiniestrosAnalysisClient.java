package ar.edu.utn.frba.arbiter.expedientes.services;

import ar.edu.utn.frba.arbiter.expedientes.dto.ExpedienteRequest;

public interface SiniestrosAnalysisClient {

    AnalysisResult analyze(ExpedienteRequest expediente);
}
