package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.time.LocalDateTime;

/**
 * Subset of {@link CaseRequest} the wizard has in hand before step 3 (document upload) — no
 * branch/product/claimCause/description/insuredItem/etc. yet, since the eligibility check
 * (vigencia, carencia, mora) doesn't need them either.
 */
public record EligibilityCheckRequest(
        @NotBlank String insuredId,
        @NotBlank String policyNumber,
        @NotNull @PastOrPresent LocalDateTime eventDate,
        LocalDateTime policeReportAt
) {
}
