package ar.edu.utn.frba.arbiter.siniestros.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record ReglasNegocio(
        String branchId,
        String claimCauseId,
        List<String> rules,
        List<String> exclusions,
        List<String> fastTrackCriteria
) {}
