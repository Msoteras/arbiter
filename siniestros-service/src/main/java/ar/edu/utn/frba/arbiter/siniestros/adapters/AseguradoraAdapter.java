package ar.edu.utn.frba.arbiter.siniestros.adapters;

import ar.edu.utn.frba.arbiter.siniestros.dto.HistorialAsegurado;
import ar.edu.utn.frba.arbiter.siniestros.dto.PolizaAsegurado;

public interface AseguradoraAdapter {

    PolizaAsegurado obtenerPoliza(String polizaNumero);

    HistorialAsegurado obtenerHistorial(String aseguradoDni);
}
