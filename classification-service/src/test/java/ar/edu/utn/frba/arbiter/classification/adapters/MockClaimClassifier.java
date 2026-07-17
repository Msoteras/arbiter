package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationRequest;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;

import java.util.List;

/**
 * Canned implementation for unit tests. Does not call Ollama.
 */
public class MockClaimClassifier implements ClaimClassifier {

    @Override
    public ClassificationResponse classify(ClassificationRequest request) {
        return new ClassificationResponse(
                Classification.FALTA_DOCUMENTACION,
                List.of("Report consistente con el hecho generador declarado",
                        "Sin historial de claims previos",
                        "Documentación completa y verificable"),
                0.95,
                false
        );
    }
}
