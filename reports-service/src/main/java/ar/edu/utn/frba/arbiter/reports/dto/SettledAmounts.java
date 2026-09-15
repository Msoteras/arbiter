package ar.edu.utn.frba.arbiter.reports.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * La plata del período: cuánto se comprometió a pagar la compañía y cómo se llegó a ese número
 * desde lo que el asegurado reclamó.
 *
 * <p>Sólo las liquidaciones <b>autorizadas</b>. Una que espera la firma del referente todavía no es
 * un compromiso —puede volver con un motivo y rehacerse por otro monto—, y sumarla diría que la
 * compañía se obligó por una plata que nadie firmó.
 *
 * <p>Se ancla en la fecha en que se confirmó la liquidación y no en la del expediente: es el día en
 * que la obligación nace, y es el que hace que el número del período cierre con el del trimestre.
 *
 * @param settlements liquidaciones autorizadas en el período
 * @param settled     la suma de lo liquidado — el número que el referente busca primero
 * @param average     lo liquidado por siniestro; null sin liquidaciones, porque un período sin
 *                    liquidar nada no tiene un promedio de cero
 * @param claimed     lo que reclamaban esos mismos expedientes
 * @param claimedCases sobre cuántas de las liquidaciones se pudo sumar eso. El monto reclamado lo
 *                    carga el asegurado en la denuncia y no es obligatorio, así que hay expedientes
 *                    que no lo traen. Va aparte para que la pantalla sepa si la comparación
 *                    "liquidado contra reclamado" cubre todo el período o sólo una parte: con dos
 *                    poblaciones distintas el porcentaje no significa lo que parece
 * @param deductible  cuánto se descontó por franquicia
 * @param installments cuánto se descontó por cuotas de la póliza todavía no pagadas
 * @param overdue     cuánto se descontó por saldo en mora
 */
public record SettledAmounts(
        long settlements,
        BigDecimal settled,
        BigDecimal average,
        BigDecimal claimed,
        long claimedCases,
        BigDecimal deductible,
        BigDecimal installments,
        BigDecimal overdue
) {

    public static final SettledAmounts NONE = new SettledAmounts(
            0, BigDecimal.ZERO, null, BigDecimal.ZERO, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    public static SettledAmounts of(long settlements, BigDecimal settled, BigDecimal claimed,
                                    long claimedCases, BigDecimal deductible,
                                    BigDecimal installments, BigDecimal overdue) {
        BigDecimal average = settlements == 0
                ? null
                : settled.divide(BigDecimal.valueOf(settlements), 2, RoundingMode.HALF_UP);
        return new SettledAmounts(settlements, settled, average, claimed, claimedCases,
                deductible, installments, overdue);
    }
}
