package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.common.enums.FraudRecordSource;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What the analyst says when they determine that a case ended in fraud.
 *
 * <p>The reason has a floor and not just a "not blank": this is the text a colleague will read
 * years from now, next to a mark on a person, when they have to justify why the claim in front of
 * them was treated differently. "fraude" is not that text.
 *
 * @param source who backs the determination — {@code EXPERT_BACKED} requires the case to have an
 *               expert report with {@code FRAUD_CONFIRMED}; {@code ANALYST_DECLARED} is the
 *               analyst's own call and never reaches the engine
 */
public record RegisterFraudRecordRequest(
        @NotNull FraudRecordSource source,
        @Size(min = 20, max = 2000, message = "El motivo del antecedente tiene que explicar el caso (mínimo 20 caracteres)")
        @NotNull String reason
) {}
