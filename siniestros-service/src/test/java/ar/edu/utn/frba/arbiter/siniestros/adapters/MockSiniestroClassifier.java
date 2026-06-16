package ar.edu.utn.frba.arbiter.siniestros.adapters;

import ar.edu.utn.frba.arbiter.common.enums.Clasificacion;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionRequest;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionResponse;

import java.util.List;

/**
 * Implementación canned para tests unitarios. No llama a Ollama.
 */
public class MockSiniestroClassifier implements SiniestroClassifier {

    @Override
    public ClasificacionResponse clasificar(ClasificacionRequest request) {
        return new ClasificacionResponse(
                Clasificacion.FAST_TRACK,
                List.of("Denuncia consistente con el hecho generador declarado",
                        "Sin historial de siniestros previos",
                        "Documentación completa y verificable"),
                0.95
        );
    }
}
