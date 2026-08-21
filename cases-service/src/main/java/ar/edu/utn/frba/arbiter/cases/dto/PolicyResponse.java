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
        boolean upToDate,
        BigDecimal insuredAmount,
        BigDecimal deductible,
        List<Coverage> coverages
) {

    @Builder
    public record Coverage(
            String code,
            String description,
            BigDecimal insuredAmount,
            BigDecimal deductible
    ) {}
}
