package ar.edu.utn.frba.arbiter.cases.dto;

/** Acknowledgement of a command on a case; {@code status} names the outcome, not a {@code CaseStatus}. */
public record CaseActionResponse(Long caseId, String status) {
}
