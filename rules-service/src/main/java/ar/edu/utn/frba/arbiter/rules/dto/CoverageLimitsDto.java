package ar.edu.utn.frba.arbiter.rules.dto;

/**
 * Límites intrínsecos de una cobertura que el motor evalúa por código (no el LLM): plazo de denuncia
 * (D11) y tope de eventos por año (D10). Son columnas de {@code coverage} (las edita el referente en
 * la solapa Coberturas). {@code null} = no configurado ⇒ la regla no se evalúa.
 */
public record CoverageLimitsDto(
        Long reportDeadlineHours,
        Integer maxEventsPerYear
) {

    public static CoverageLimitsDto empty() {
        return new CoverageLimitsDto(null, null);
    }

    public boolean isEmpty() {
        return reportDeadlineHours == null && maxEventsPerYear == null;
    }
}
