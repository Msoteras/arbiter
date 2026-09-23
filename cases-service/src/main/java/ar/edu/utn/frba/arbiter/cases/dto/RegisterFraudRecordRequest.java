package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.common.enums.FraudRecordSource;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The reason has a minimum length, not just "not blank": it's what a colleague will read years later
 * to justify treating a claim differently.
 *
 * @param source {@code EXPERT_BACKED} requires an expert report with {@code FRAUD_CONFIRMED};
 *               {@code ANALYST_DECLARED} never reaches the rules engine
 */
public record RegisterFraudRecordRequest(
        @NotNull FraudRecordSource source,
        @Size(min = 20, max = 2000, message = "El motivo del antecedente tiene que explicar el caso (mínimo 20 caracteres)")
        @NotNull String reason
) {}
