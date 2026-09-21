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
        List<String> engineEvaluation,
        /**
         * The branch's whole claim cause catalog with its coverage status, so the model can tell
         * whether the account describes the declared cause or a different one. Without it the model
         * only ever sees the cause that was picked, and "this sounds more like a hurto" is a
         * conclusion it has no vocabulary to reach.
         *
         * <p>It is also what closes the output: {@code suggestedClaimCause} is restricted by schema
         * to these names, so no fuzzy matching is needed to map the answer back to an id.
         */
        List<ClaimCauseOption> claimCauseCatalog
) {

    /**
     * One claim cause of the branch. {@code covered} is the engine's word, not the model's: it
     * comes from the coverage's {@code COVERAGE_EXCLUSION} rule (CLAUDE.md #4).
     */
    public record ClaimCauseOption(Long id, String name, boolean covered) {}
}
