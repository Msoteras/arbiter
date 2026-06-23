package ar.edu.utn.frba.arbiter.siniestros.adapters;

import ar.edu.utn.frba.arbiter.siniestros.dto.HistorialAsegurado;
import ar.edu.utn.frba.arbiter.siniestros.dto.PolizaAsegurado;

public interface AseguradoraAdapter {

    PolizaAsegurado getPolicy(String policyNumber);

    HistorialAsegurado getHistory(String insuredId);
}
