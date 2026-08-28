package ar.edu.utn.frba.arbiter.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Shared contract: the facts of a claim, sent by cases-service to classification-service
 * for analysis. {@code attachmentsOcr} carries already-extracted document text when the
 * caller has it (classification fills it in internally otherwise).
 */
@Builder
public record ClaimReport(
        @NotBlank String branch,
        @NotBlank String product,
        @NotBlank String claimCause,
        // Coverage id — the DER scopes a business rule (Fast Track, scoring) by rama + cobertura,
        // not by hecho generador. Nullable: the isolated test flow builds a claim without a case;
        // the real cases->classification flow always sets it from Case.coverage.
        Long coverageId,
        // Claim cause (hecho generador) id — the coverage-exclusion evaluator matches against it
        // by id, not by name (names repeat across branches; the id is unambiguous). Nullable for
        // the same reason as coverageId: the isolated test flow has no case behind it.
        Long claimCauseId,
        @NotBlank String insuredItem,
        @NotBlank String insuredId,
        @NotBlank String policyNumber,
        @NotBlank String description,
        @NotNull LocalDateTime eventDate,
        @NotBlank String eventLocation,
        BigDecimal claimedAmount,
        // When the claim was reported to the insurer (case creation). Used by the reporting deadline
        // rule (D11): reportedAt - eventDate vs coverage.report_deadline_hours. Nullable: the
        // isolated flow (no case) doesn't have it, and there the rule isn't evaluable.
        LocalDateTime reportedAt,
        // When the insured SAYS they filed the police report. It's their STATEMENT, not what the
        // certificate says: when extraction reads the date off the paper it travels separately, and
        // crossing the two is precisely the signal (D12). Nullable — not every claim cause involves
        // a police report, and there the deadline rule isn't evaluable.
        LocalDateTime policeReportAt,
        // Whether the insured gave consent to send their images to external services (Google Vision
        // web search). Captured during onboarding, not per claim. When it isn't granted the internal
        // CLIP analysis still runs (it never leaves the host), only the web escalation is skipped.
        //
        // Boxed, and absence means NO: a primitive would make this record undeserializable from any
        // JSON that omits the field, because Jackson 3 keeps FAIL_ON_NULL_FOR_PRIMITIVES on and a
        // record's missing property reaches the canonical constructor as null. Fail-closed is also
        // the only safe reading of a consent flag — "nobody said" cannot mean "go ahead and send
        // their photos to a third party".
        Boolean imageConsent,
        List<String> attachmentsOcr
) {}
