package ar.edu.utn.frba.arbiter.common.dto;

import ar.edu.utn.frba.arbiter.common.enums.Classification;
import lombok.Builder;

import java.util.List;

/**
 * Shared contract: polling response for a case's classification. Fields other than
 * {@code caseId} are null until the analysis finishes; the case lifecycle/state is
 * cases-service's responsibility.
 *
 * <p>{@code forensicReport} carries the structured image-fraud analysis for the analyst UI
 * (analyst-only — never shown to the insured). Null when no analysis ran: Fast Track, or a
 * claim with no image attachments.
 */
@Builder
public record ClaimResponse(
        Long caseId,
        Classification classification,
        Double confidence,
        List<String> factors,
        boolean deterministicFastTrack,
        ImageForensicReport forensicReport
) {}
