package ar.edu.utn.frba.arbiter.cases.dto;

import java.time.Instant;

/** The verdict a resolved case stands on, as the analyst recorded it. */
public record AnalystDecisionResponse(
        /** APPROVE | REJECT (older rows: APROBAR | RECHAZAR). */
        String decision,
        String justification,
        Instant decidedAt
) {
}
