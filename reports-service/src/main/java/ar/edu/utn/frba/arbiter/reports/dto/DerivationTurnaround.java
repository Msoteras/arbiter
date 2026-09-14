package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * Cuánto tarda en contestar cada clase de tercero al que se deriva un expediente: el estudio
 * liquidador con su peritaje, el servicio técnico con su presupuesto o su reparación.
 *
 * <p>Es el tramo más caro del proceso y el que la compañía no controla: mientras dura, el plazo
 * legal está interrumpido y el expediente no se mueve. Hasta ahora el tablero lo tenía escondido
 * adentro del "tiempo esperando a terceros", que dice cuánto pero no a quién.
 *
 * <p>El período se cuenta por la fecha de <b>derivación</b>, no por la de respuesta: la pregunta es
 * cómo responden los terceros a lo que se les mandó en el período, y anclarlo en la respuesta
 * dejaría afuera justamente a los que todavía no contestaron, que son los que hay que mirar.
 *
 * @param providerType     el tipo de proveedor, crudo como lo guarda cases-service; la etiqueta en
 *                         castellano la pone el frontend, igual que con los estados
 * @param derived          derivaciones hechas en el período
 * @param answered         cuántas ya volvieron con su informe
 * @param averageHours     cuánto tardaron en volver, promedio sobre las que contestaron; null si no
 *                         contestó ninguna. No incluye a las pendientes: mientras no contesten no
 *                         se sabe cuánto van a tardar, y contarlas con el tiempo transcurrido hasta
 *                         hoy haría bajar el promedio cada vez que se abre el tablero
 */
public record DerivationTurnaround(
        String providerType,
        long derived,
        long answered,
        Double averageHours
) {

    /** Las que salieron y todavía no volvieron. */
    public long pending() {
        return derived - answered;
    }
}
