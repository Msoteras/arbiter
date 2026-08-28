package ar.edu.utn.frba.arbiter.cases.services;

public record AnalysisResult(
        String classification,
        double confidence,
        String detail
) {
}
