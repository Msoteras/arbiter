package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param closure why the case ends without a verdict
 * @param reason  mandatory: the only explanation the history keeps of why it was closed
 */
public record CloseCaseRequest(
        @NotNull(message = "closure is required") Closure closure,
        @NotBlank(message = "reason is required")
        @Size(max = 1000, message = "reason must be at most 1000 characters")
        String reason
) {

    /** The two closures an analyst can make; prescription is a decision, not a closure. */
    public enum Closure {
        WITHDRAWN,
        VOIDED;

        public CaseStatus status() {
            return CaseStatus.valueOf(name());
        }
    }
}
