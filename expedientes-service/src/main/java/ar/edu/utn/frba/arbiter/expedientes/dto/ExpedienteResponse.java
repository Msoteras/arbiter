package ar.edu.utn.frba.arbiter.expedientes.dto;

public record ExpedienteResponse(
        Long expedienteId,
        String status,
        String policyNumber,
        String insuredId,
        String analysisClassification,
        double analysisConfidence,
        String analysisDetail
) {
}
