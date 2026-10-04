package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The analyst corrects the claim cause the insured declared; the coverage follows from it.
 *
 * @param reason mandatory: it is the only record of why the analyst overrode the insured's own words
 */
public record ClaimCauseCorrectionRequest(
        @NotNull Long claimCauseId,
        @NotBlank @Size(max = 1000) String reason) {}
