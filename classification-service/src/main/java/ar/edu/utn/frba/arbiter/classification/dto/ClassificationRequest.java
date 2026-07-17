package ar.edu.utn.frba.arbiter.classification.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record ClassificationRequest(
        String branch,
        String product,
        String claimCause,
        String insuredItem,
        String description,
        List<String> attachmentsOcr,
        String insurerRules,
        String insuredHistory
) {}
