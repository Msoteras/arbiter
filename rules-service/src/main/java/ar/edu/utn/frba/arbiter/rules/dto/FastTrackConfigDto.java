package ar.edu.utn.frba.arbiter.rules.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

import java.util.List;

/**
 * The deterministic Fast Track thresholds a referente configures for one (rama, cobertura).
 * Mirrors classification-service's {@code BusinessRules.FastTrackThresholds} field for field —
 * this is exactly what gets serialized into {@code insurer_rule.configuration} (JSONB) and what
 * classification will read back to gate Fast Track. A {@code null} field means "that criterion
 * doesn't apply"; an all-null config means the coverage has no Fast Track configured.
 */
public record FastTrackConfigDto(
        @DecimalMin("0.0") @DecimalMax("1.0") Double maxClaimedAmountRatio,
        @Min(0) Integer maxPriorClaims,
        /**
         * Ventana en meses para contar {@code maxPriorClaims}. Null = sin ventana, o sea el
         * histórico completo del asegurado — que era el comportamiento anterior, cuando el campo
         * existía solo en la UI y se descartaba: "máximo 1 siniestro previo" significaba
         * *nunca en la vida*, no *en los últimos 24 meses* (D14).
         */
        @Min(0) Integer priorClaimsWindowMonths,
        /** Antigüedad mínima de la póliza al momento del hecho, en meses. Null = no se exige. */
        @Min(0) Integer minPolicyAgeMonths,
        Boolean requiresUpToDatePolicy,
        List<String> requiredDocumentTypes,
        /**
         * Los mismos criterios en castellano, para que el LLM los lea. No deciden nada —el gate son
         * los umbrales de arriba— pero viajan al prompt como descripción de la política de Fast
         * Track de la aseguradora. Antes salían hardcodeados del {@code MockRulesAdapter}, así que
         * el texto podía contradecir a los números que el referente configuraba (D14).
         */
        List<String> criteria
) {
    public static FastTrackConfigDto empty() {
        return new FastTrackConfigDto(null, null, null, null, null, null, null);
    }
}
