package ar.edu.utn.frba.arbiter.rules.dto;

/**
 * Límites intrínsecos de una cobertura que el motor evalúa por código (no el LLM): plazo de denuncia
 * (D11), tope de eventos por año (D10) y carencia (D9). Son columnas de {@code coverage} (las edita
 * el referente en la solapa Coberturas). {@code null} = no configurado ⇒ la regla no se evalúa.
 */
public record CoverageLimitsDto(
        Long reportDeadlineHours,
        Integer maxEventsPerYear,
        /**
         * Carencia: días desde el alta de la póliza durante los cuales la cobertura todavía no
         * aplica, aunque la póliza esté vigente. Existe para que no se contrate un seguro por un
         * hecho ya ocurrido o inminente. {@code null} = sin carencia.
         */
        Integer waitingPeriodDays,
        /**
         * Si la cobertura alcanza al grupo familiar conviviente o solo al titular (D9). La fuente es
         * <b>la cobertura</b>, no {@code poliza.cubre_grupo_familiar} de la BD Aseguradora: las dos
         * existen y ya se contradicen, y la que configura el referente es la que manda (decisión de
         * Fede, 10/08).
         */
        Boolean coversFamilyGroup,
        /** Si un siniestro liquidado agota la cobertura para el período (D9). */
        Boolean claimExhaustsCoverage
) {

    public static CoverageLimitsDto empty() {
        return new CoverageLimitsDto(null, null, null, null, null);
    }

    public boolean isEmpty() {
        return reportDeadlineHours == null && maxEventsPerYear == null && waitingPeriodDays == null
                && coversFamilyGroup == null && claimExhaustsCoverage == null;
    }
}
