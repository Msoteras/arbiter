package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;

import java.time.LocalDateTime;

/**
 * Subset of {@link CaseRequest} the wizard has in hand before step 3 (document upload) — no
 * branch/product/claimCause/description/insuredItem/etc. yet, since the eligibility check
 * (vigencia, carencia, mora) doesn't need them either.
 *
 * <p>{@code eventDate} is optional, not {@code @NotNull}: the wizard calls this endpoint twice —
 * once right after picking a policy (step 1, no date yet) to catch arrears
 * ({@code PolicyEligibilityValidator#assertPolicyStanding} doesn't read the date at all), and
 * again once the event date is filled (step 2) to also cover vigencia/carencia, which do need
 * it. The validator already treats a null date as "skip the checks that need one", not as an
 * error — it's not a workaround, it's how the method was written from the start.
 */
public record EligibilityCheckRequest(
        @NotBlank String insuredId,
        @NotBlank String policyNumber,
        @PastOrPresent LocalDateTime eventDate,
        LocalDateTime policeReportAt
) {
}
