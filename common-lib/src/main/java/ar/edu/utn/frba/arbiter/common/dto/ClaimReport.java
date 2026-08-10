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
        // Cuándo se denunció el siniestro a la aseguradora (alta del caso). Lo usa la regla del plazo
        // de denuncia (D11): reportedAt - eventDate vs coverage.report_deadline_hours. Nullable: el
        // flujo aislado (sin caso) no lo tiene, y ahí la regla no es evaluable.
        LocalDateTime reportedAt,
        List<String> attachmentsOcr
) {}
