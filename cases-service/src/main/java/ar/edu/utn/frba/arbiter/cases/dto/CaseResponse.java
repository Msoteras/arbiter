package ar.edu.utn.frba.arbiter.cases.dto;

public record CaseResponse(
        Long caseId,
        String status,
        String policyNumber,
        String insuredId,
        String analysisClassification,
        double analysisConfidence,
        String analysisDetail
) {
}
