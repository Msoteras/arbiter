package ar.edu.utn.frba.arbiter.rules.dto;

import java.util.List;

/** Save confirmation for a coverage's exclusion: the insurer_rule row as it ended up. */
public record CoverageExclusionResponse(
        Long id,
        Long branchId,
        Long coverageId,
        List<Long> excludedClaimCauseIds
) {}
