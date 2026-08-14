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
        String insuredHistory,
        // Verdict of the hard rules the engine already evaluated in code (claim cause coverage,
        // reporting deadline, validity, event cap). Injected into the prompt as established fact so
        // the LLM doesn't re-decide them (D4a step 6). Empty = no breaches.
        List<String> engineEvaluation
) {}
