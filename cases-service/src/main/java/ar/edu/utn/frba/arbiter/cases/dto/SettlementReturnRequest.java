package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Por qué el referente devuelve la liquidación al analista.
 *
 * @param reason obligatorio: devolverla sin decir por qué le deja al analista un expediente
 *               frenado y ninguna pista de qué corregir. Es lo único que el referente aporta acá,
 *               así que un campo vacío convierte la devolución en ruido
 */
public record SettlementReturnRequest(
        @NotBlank(message = "reason is required") String reason
) {}
