package ar.edu.utn.frba.arbiter.siniestros.adapters;

import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionRequest;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionResponse;

public interface SiniestroClassifier {

    ClasificacionResponse clasificar(ClasificacionRequest request);
}
