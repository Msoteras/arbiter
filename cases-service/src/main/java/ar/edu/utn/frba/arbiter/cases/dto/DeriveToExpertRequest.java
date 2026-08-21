package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The analyst deriving a case to an external expert.
 *
 * <p>No analyst id: like {@link AnalystDecisionRequest}, it is resolved from the caller's JWT.
 * A client-supplied id would let anyone attribute the derivation to someone else.
 *
 * @param expertFirmId whose id has to be in the catalog — a free-text address would mean no
 *                     record of who the insurer actually works with.
 * @param reason       why the analyst is deriving. Required: a derivation with no stated reason
 *                     is the kind of thing the audit trail exists to prevent.
 */
public record DeriveToExpertRequest(
        @NotNull(message = "expertFirmId is required") Long expertFirmId,
        @NotBlank(message = "reason is required") String reason
) {}
