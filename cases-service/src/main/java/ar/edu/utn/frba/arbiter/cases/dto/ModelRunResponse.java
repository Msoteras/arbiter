package ar.edu.utn.frba.arbiter.cases.dto;

import java.time.Instant;

/**
 * The model run behind a recommendation, as audited in {@code llm_analysis} (Disposición SSN
 * 2/2023): which model, with which prompt version, when and how long it took.
 */
public record ModelRunResponse(
        String model,
        String promptVersion,
        Instant analyzedAt,
        /** Null when the run didn't record it. */
        Integer latencyMs
) {}
