package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;

import java.time.LocalDateTime;

/**
 * {@code eventDate} is optional: the wizard calls this once right after picking a policy (to catch
 * arrears) and again with the date, to also check the coverage period and waiting period.
 */
public record EligibilityCheckRequest(
        @NotBlank String insuredId,
        @NotBlank String policyNumber,
        @PastOrPresent LocalDateTime eventDate,
        LocalDateTime policeReportAt
) {
}
