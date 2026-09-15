package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * Cumplimiento del plazo legal del art. 56 sobre los expedientes decididos en el período.
 *
 * <p>Es la única métrica regulatoria del tablero, y hay que leerla aparte del objetivo de
 * resolución: el objetivo es una meta que la compañía se pone y puede cambiar cuando quiera;
 * éste es el plazo que fija la ley para pronunciarse sobre un siniestro. Pasarse del objetivo es
 * un problema de gestión; pasarse de éste es un problema regulatorio.
 *
 * <p>Cada expediente trae su propia fecha límite, que cases-service ya mantiene con la regla del
 * procedimiento: las derivaciones la congelan, y al cumplirse el requerimiento vuelven a correr
 * 30 días <b>enteros</b>, no los que quedaban. Por eso acá no se recalcula nada — se compara la
 * fecha en que se decidió contra la fecha límite que el expediente tenía ese día.
 *
 * @param decided expedientes decididos en el período. Los caducados quedan afuera, igual que en el
 *                resto del tablero: nadie los decidió, así que no hay pronunciamiento que fechar.
 * @param onTime  cuántos de ésos se decidieron el día del vencimiento o antes
 * @param rate    {@code onTime} sobre {@code decided}; null cuando no hubo decisiones, porque un
 *                período sin decidir nada no tiene un cumplimiento del 0%, tiene uno desconocido
 */
public record LegalDeadline(long decided, long onTime, Double rate) {

    public static LegalDeadline of(long decided, long onTime) {
        return new LegalDeadline(decided, onTime, decided == 0 ? null : (double) onTime / decided);
    }
}
