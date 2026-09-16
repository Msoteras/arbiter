package ar.edu.utn.frba.arbiter.rules.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * El objetivo de resolución de la aseguradora: en cuántos días se propone cerrar un siniestro.
 *
 * <p><b>No es el plazo legal.</b> Ese vive por expediente en {@code cases.response_deadline} y
 * pasarse es un problema regulatorio; esto es una meta de gestión que fija el referente y que
 * puede ser más exigente que la ley. El tablero los mide por separado a propósito.
 *
 * <p>Configuración, no regla: no la evalúa el motor, no bloquea nada y no deja
 * {@code rule_result}. La lee el tablero para decir cuántos expedientes decididos en el período se
 * pasaron de lo que la compañía se propuso.
 *
 * @param enabled    si la aseguradora tiene un objetivo fijado. En false el tablero no muestra la
 *                   comparación, en vez de mostrarla contra un número inventado.
 * @param targetDays días desde la denuncia hasta la decisión. Con el objetivo apagado no se usa.
 */
public record ResolutionTargetDto(
        boolean enabled,
        // El piso de 1 es aritmético (un objetivo de 0 días no se puede cumplir nunca) y el techo
        // de 365 es el mismo año que topea el período del tablero: más que eso no se podría medir.
        @Min(1) @Max(365) Integer targetDays
) {

    /** Lo que ve una aseguradora que nunca fijó objetivo. */
    public static ResolutionTargetDto unset() {
        return new ResolutionTargetDto(false, null);
    }
}
