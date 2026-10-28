package ar.edu.utn.frba.arbiter.cases.dto;

import java.time.Instant;
import java.util.List;

/**
 * What the engine board starts from before the live steps arrive.
 *
 * @param active          queued or being classified, oldest first
 * @param classifiedToday finished or failed today, latest first
 * @param serverTime      lets the board run its clocks against the server's, not the browser's
 * @param topic           where the next steps arrive; handed over rather than built by the client,
 *                        since it includes the tenant
 */
public record EngineBoardResponse(
        List<EngineCaseResponse> active,
        List<EngineCaseResponse> classifiedToday,
        Instant serverTime,
        String topic) {
}
