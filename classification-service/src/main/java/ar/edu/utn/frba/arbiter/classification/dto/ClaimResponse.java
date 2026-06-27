package ar.edu.utn.frba.arbiter.classification.dto;

import ar.edu.utn.frba.arbiter.common.enums.Classification;
import lombok.Builder;

import java.util.List;

/**
 * Polling response for a claim's classification. Fields other than {@code claimId}
 * are null until a ClassificationLog exists for it — that's the only signal this module
 * gives about whether the analysis is done; claim lifecycle/state is cases-service's job.
 */
@Builder
public record ClaimResponse(
        Long claimId,
        Classification classification,
        Double confidence,
        List<String> factors,
        boolean deterministicFastTrack
) {}
