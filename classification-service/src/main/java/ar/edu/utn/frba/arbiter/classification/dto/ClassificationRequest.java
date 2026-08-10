package ar.edu.utn.frba.arbiter.classification.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Builder
public record ClassificationRequest(
        String branch,
        String product,
        String claimCause,
        String insuredItem,
        String description,
        LocalDateTime eventDate,
        String eventLocation,
        BigDecimal claimedAmount,
        List<String> attachmentsOcr,
        String insurerRules,
        String insuredHistory
) {}
