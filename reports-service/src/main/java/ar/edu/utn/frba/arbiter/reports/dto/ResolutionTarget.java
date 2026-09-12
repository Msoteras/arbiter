package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * El objetivo de resolución de la aseguradora y cómo le fue contra él en el período.
 *
 * <p>Espejo de lo que devuelve rules-service ({@code ResolutionTargetDto}) más el conteo que sólo
 * este módulo puede calcular. El objetivo lo fija el referente y es una <b>meta de gestión</b>: no
 * es el plazo legal, que vive por expediente y cuyo incumplimiento es otro problema.
 *
 * @param enabled    si la aseguradora tiene objetivo fijado. En false el tablero muestra el tiempo
 *                   promedio solo, sin comparación — igual que si rules-service no respondiera.
 * @param targetDays los días que se propuso la compañía; null con el objetivo apagado
 * @param exceeded   cuántos de los expedientes DECIDIDOS en el período tardaron más que eso. Sobre
 *                   los decididos y no sobre todos los resueltos, por el mismo motivo que el
 *                   promedio: un caducado no lo resolvió nadie, midió el silencio del asegurado.
 *                   Cero mientras el objetivo esté apagado.
 */
public record ResolutionTarget(boolean enabled, Integer targetDays, long exceeded) {

    /** Sin objetivo: la aseguradora no lo configuró, o no se pudo leer. */
    public static final ResolutionTarget UNSET = new ResolutionTarget(false, null, 0);

    public ResolutionTarget withExceeded(long exceeded) {
        return new ResolutionTarget(enabled, targetDays, exceeded);
    }
}
