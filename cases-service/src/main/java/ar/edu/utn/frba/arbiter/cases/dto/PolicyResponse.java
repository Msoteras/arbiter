package ar.edu.utn.frba.arbiter.cases.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Póliza vista por el asegurado para autocompletar el alta de denuncia. Incluye la
 * aseguradora ({@code insurerId}/{@code insurerName}): un mismo asegurado puede tener
 * pólizas de distintas aseguradoras en la plataforma, y las ve centralizadas acá.
 * Los datos salen de la BD Aseguradora vía {@code InsurerAdapter}.
 *
 * <p>{@code effectiveFrom}/{@code effectiveTo} llevan hora, no solo fecha: la póliza modelo (BBVA)
 * fija la vigencia con hora exacta ("desde las 12:00 hs del..."), y comparar solo por fecha da
 * falsos aceptados en el borde — un siniestro dos horas antes de que arranque la vigencia, mismo
 * día, pasaba el chequeo. {@code aseguradora_*.poliza.vigencia_desde/hasta} es {@code timestamptz}.
 */
@Builder
public record PolicyResponse(
        String policyNumber,
        String insurerId,
        String insurerName,
        String insuredName,
        String insuredId,
        String contactEmail,
        String contactPhone,
        String branch,
        String insuredItem,
        String product,
        LocalDateTime effectiveFrom,
        LocalDateTime effectiveTo,
        Validity validity,
        boolean upToDate,
        BigDecimal insuredAmount,
        BigDecimal deductible,
        List<Coverage> coverages
) {

    /**
     * Si la póliza cubre hoy, todavía no arrancó, o ya venció.
     *
     * <p>Viaja calculado y no se deriva de las fechas del otro lado, por la misma razón que
     * {@code upToDate}: {@code vigencia_desde/hasta} son {@code TIMESTAMP} sin zona, así que cada
     * consumidor los interpretaba en SU huso. El backend corre en UTC y el navegador del asegurado
     * en hora argentina — tres horas de diferencia —, y el día que una póliza vencía había una
     * ventana en la que el portal la mostraba "Vigente" mientras el alta de denuncia ya la había
     * sacado de la lista. Un solo reloj decide; los dos lados leen la misma respuesta.
     */
    public enum Validity {
        CURRENT,
        NOT_YET_ACTIVE,
        EXPIRED;

        public static Validity at(LocalDateTime from, LocalDateTime to, LocalDateTime now) {
            if (to != null && to.isBefore(now)) {
                return EXPIRED;
            }
            return from != null && from.isAfter(now) ? NOT_YET_ACTIVE : CURRENT;
        }
    }

    /**
     * Una cobertura contratada en la póliza. Son VARIAS: una póliza de celulares cubre robo y
     * hurto, cada una con su suma asegurada y su franquicia propias.
     *
     * @param deductible    franquicia en valor absoluto, ya calculada sobre {@code insuredAmount} —
     *                      es lo que consumen las reglas
     * @param deductiblePct la misma franquicia como la da la compañía, en puntos porcentuales
     *                      (10.00 = 10%). Viaja además del absoluto porque es el dato crudo que se
     *                      persiste en {@code policy_coverage}: guardar solo el derivado lo deja
     *                      desfasado apenas cambia la suma asegurada que lo produjo.
     */
    @Builder
    public record Coverage(
            String code,
            String description,
            BigDecimal insuredAmount,
            BigDecimal deductible,
            BigDecimal deductiblePct
    ) {}
}
