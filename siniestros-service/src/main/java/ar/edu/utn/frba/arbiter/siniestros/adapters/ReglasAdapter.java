package ar.edu.utn.frba.arbiter.siniestros.adapters;

import ar.edu.utn.frba.arbiter.siniestros.dto.ReglasNegocio;

public interface ReglasAdapter {

    ReglasNegocio obtenerReglas(String ramoId, String hechoGeneradorId);
}
