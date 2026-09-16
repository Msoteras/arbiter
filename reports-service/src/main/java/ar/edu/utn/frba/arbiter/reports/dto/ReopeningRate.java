package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * Cuántos de los expedientes resueltos en el período habían sido reabiertos alguna vez.
 *
 * <p>Es una métrica de <b>calidad de la decisión</b>, no de volumen: se lee al lado de la
 * coincidencia con el modelo. Una tasa alta dice que se está decidiendo rápido y mal — el
 * expediente se cerró, alguien lo volvió a abrir y hubo que decidirlo de nuevo.
 *
 * <p>Se cuenta por expediente y no por reapertura: uno que se reabrió tres veces es un expediente
 * con problemas, no tres. Y sobre los resueltos en el período, no sobre las reaperturas ocurridas
 * en él, para que el denominador sea el mismo que el de las demás tasas del resumen.
 *
 * @param resolved expedientes que cerraron en el período, caducados incluidos: un caducado también
 *                 se puede haber reabierto, y si pasó es igual de sintomático
 * @param reopened cuántos de ésos tienen al menos una reapertura en su historial
 * @param rate     {@code reopened} sobre {@code resolved}; null sin expedientes resueltos
 */
public record ReopeningRate(long resolved, long reopened, Double rate) {

    public static ReopeningRate of(long resolved, long reopened) {
        return new ReopeningRate(resolved, reopened, resolved == 0 ? null : (double) reopened / resolved);
    }
}
