package ar.edu.utn.frba.arbiter.cases.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Póliza vista por el asegurado para autocompletar el alta de denuncia. Incluye la
 * aseguradora ({@code insurerId}/{@code insurerName}): un mismo asegurado puede tener
 * pólizas de distintas aseguradoras en la plataforma, y las ve centralizadas acá.
 * Los datos salen de la BD Aseguradora vía {@code InsurerAdapter} (mock por ahora).
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
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
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
