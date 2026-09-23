package ar.edu.utn.frba.arbiter.cases.dto;

/**
 * @param analystId   {@code claims_analyst} id, local to the insurer's schema
 * @param activeCases cases not in a final status; analysts with none still appear, with zero
 */
public record AnalystWorkloadResponse(Long analystId, String name, long activeCases) {
}
