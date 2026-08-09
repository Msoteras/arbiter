package ar.edu.utn.frba.arbiter.cases.dto;

import java.math.BigDecimal;
import java.util.List;

/** Full shape of a coverage for the referente's Coberturas tab — mirrors the frontend's Coverage model. */
public record CoverageDetailResponse(
        Long id,
        String name,
        String clause,
        BigDecimal deductibleRatio,
        Integer reportingWindowDays,
        Integer maxAnnualClaims,
        List<String> exclusions) {}
