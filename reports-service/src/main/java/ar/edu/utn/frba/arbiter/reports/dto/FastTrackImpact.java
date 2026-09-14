package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * Cuánto agiliza el Fast Track, como dos números medidos uno al lado del otro.
 *
 * <p>Deliberadamente <b>no</b> es un ahorro estimado. La maqueta pedía "54 h ahorradas", que sale
 * de multiplicar la diferencia de dos promedios por la cantidad de casos: con una docena de
 * expedientes al mes eso es ruido presentado como resultado. "Fast Track: 2 d · Resto: 35 d" son
 * dos mediciones, y quien las lee saca su propia conclusión sin que le vendamos una.
 *
 * <p>Sobre los decididos en el período, el mismo universo que el promedio del resumen, así que las
 * dos mitades tienen que dar ese promedio ponderado por sus cantidades.
 *
 * @param fastTrackDecided expedientes de Fast Track decididos en el período
 * @param fastTrackHours   cuánto tardaron en promedio; null sin ninguno
 * @param standardDecided  el resto de los decididos
 * @param standardHours    cuánto tardaron en promedio; null sin ninguno
 */
public record FastTrackImpact(
        long fastTrackDecided,
        Double fastTrackHours,
        long standardDecided,
        Double standardHours
) {

    public static final FastTrackImpact NONE = new FastTrackImpact(0, null, 0, null);
}
