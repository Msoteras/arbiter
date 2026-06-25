package ar.edu.utn.frba.arbiter.siniestros.adapters;

import ar.edu.utn.frba.arbiter.common.enums.Clasificacion;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClassificationRequest;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClassificationResponse;

import java.util.List;

/**
 * Canned implementation for unit tests. Does not call Ollama.
 */
public class MockSiniestroClassifier implements SiniestroClassifier {

    @Override
    public ClassificationResponse classify(ClassificationRequest request) {
        return new ClassificationResponse(
                Clasificacion.FALTA_DOCUMENTACION,
                List.of("Denuncia consistente con el hecho generador declarado",
                        "Sin historial de siniestros previos",
                        "Documentación completa y verificable"),
                0.95,
                false
        );
    }
}
