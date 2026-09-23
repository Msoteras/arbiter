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
        // Hard-rule verdicts, injected as established fact so the LLM doesn't re-decide them.
        List<String> engineEvaluation,
        /**
         * Lets the model tell whether the account matches the declared cause; also the enum that
         * restricts {@code suggestedClaimCause} in the output schema.
         */
        List<ClaimCauseOption> claimCauseCatalog
) {

    /** {@code covered} comes from the coverage's {@code COVERAGE_EXCLUSION} rule, not from the model. */
    public record ClaimCauseOption(Long id, String name, boolean covered) {}
}
