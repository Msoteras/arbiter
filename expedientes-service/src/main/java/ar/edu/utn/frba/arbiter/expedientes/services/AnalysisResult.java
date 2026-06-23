package ar.edu.utn.frba.arbiter.expedientes.services;

public record AnalysisResult(
        String classification,
        double confidence,
        String detail
) {
}
