package ar.edu.utn.frba.arbiter.rules.dto;

import java.util.List;

/**
 * Shape of a {@code COVERAGE_EXCLUSION} rule's {@code configuration} (JSONB): the blacklist of
 * claim causes the coverage does NOT cover. A blacklist on purpose: a coverage
 * with no rule accepts everything, so adding exclusions is purely additive.
 */
public record CoverageExclusionConfig(List<Long> excludedClaimCauseIds) {

    public List<Long> excludedClaimCauseIds() {
        return excludedClaimCauseIds == null ? List.of() : excludedClaimCauseIds;
    }
}
