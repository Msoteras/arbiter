package ar.edu.utn.frba.arbiter.siniestros.adapters;

import ar.edu.utn.frba.arbiter.common.enums.Clasificacion;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionRequest;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionResponse;

import java.util.List;

/**
 * Canned implementation for unit tests. Does not call Ollama.
 */
public class MockSiniestroClassifier implements SiniestroClassifier {

    @Override
    public ClasificacionResponse classify(ClasificacionRequest request) {
        return new ClasificacionResponse(
                Clasificacion.FAST_TRACK,
                List.of("Denuncia consistente con el hecho generador declarado",
                        "Sin historial de siniestros previos",
                        "Documentación completa y verificable"),
                0.95
        );
    }
}
