package ar.edu.utn.frba.arbiter.classification.dto;

import ar.edu.utn.frba.arbiter.common.enums.Classification;
import lombok.Builder;

import java.util.List;

@Builder
public record ClassificationResponse(
        Classification classification,
        List<String> factors,
        double confidence,
        boolean deterministicFastTrack
) {}
