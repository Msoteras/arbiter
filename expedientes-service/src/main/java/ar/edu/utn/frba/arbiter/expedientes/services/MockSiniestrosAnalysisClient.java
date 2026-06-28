package ar.edu.utn.frba.arbiter.expedientes.services;

import ar.edu.utn.frba.arbiter.expedientes.dto.ExpedienteRequest;
import org.springframework.stereotype.Component;

@Component
public class MockSiniestrosAnalysisClient implements SiniestrosAnalysisClient {

    @Override
    public AnalysisResult analyze(ExpedienteRequest expediente) {
        String classification = classify(expediente);
        String detail = switch (classification) {
            case "FAST_TRACK" -> "Caso de baja complejidad detectado con la información inicial.";
            case "POTENCIAL_RIESGO" -> "Caso con evidencias de riesgo moderado, requiere revisión detallada.";
            case "FALTA_DOCUMENTACION" -> "Se detectó falta de documentación clave para continuar el análisis.";
            default -> "El expediente necesita análisis manual adicional.";
        };
        double confidence = classification.equals("REQUIERE_ANALISIS_MANUAL") ? 0.58 : 0.92;
        return new AnalysisResult(classification, confidence, detail);
    }

    private String classify(ExpedienteRequest expediente) {
        String cause = expediente.claimCause().toLowerCase();
        String location = expediente.eventLocation().toLowerCase();
        if (cause.contains("rotura") || location.contains("casa") || location.contains("hogar")) {
            return "FAST_TRACK";
        }
        if (cause.contains("robo") || cause.contains("hurto") || cause.contains("pérdida") || cause.contains("perdí")) {
            return "POTENCIAL_RIESGO";
        }
        if (expediente.description().length() < 30) {
            return "FALTA_DOCUMENTACION";
        }
        return "REQUIERE_ANALISIS_MANUAL";
    }
}
