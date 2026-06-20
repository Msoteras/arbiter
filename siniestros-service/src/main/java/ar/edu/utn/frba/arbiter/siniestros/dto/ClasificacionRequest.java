package ar.edu.utn.frba.arbiter.siniestros.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record ClasificacionRequest(
        String branch,
        String product,
        String claimCause,
        String insuredItem,
        String description,
        List<String> attachmentsOcr,
        String insurerRules,
        String insuredHistory
) {}
