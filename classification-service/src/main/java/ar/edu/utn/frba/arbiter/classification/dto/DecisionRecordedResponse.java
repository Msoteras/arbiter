package ar.edu.utn.frba.arbiter.classification.dto;

/** cases-service stores {@code classificationId} on {@code cases.classification_id}. */
public record DecisionRecordedResponse(Long caseId, String status, Long classificationId) {
}
