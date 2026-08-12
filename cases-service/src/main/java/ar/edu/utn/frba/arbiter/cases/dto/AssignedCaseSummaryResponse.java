package ar.edu.utn.frba.arbiter.cases.dto;

import java.util.Map;

/**
 * Resumen de los expedientes asignados al analista logueado, para las tarjetas de su pantalla de
 * inicio. Sale en una sola llamada en vez de un conteo por estado a la vez.
 *
 * <p>El "yo" lo resuelve el backend contra el token (no viaja un id de analista): el id es local
 * al esquema de cada aseguradora. Un rol sin perfil de analista (el referente) recibe el resumen
 * vacío (total 0), no un error.
 *
 * @param total    total de expedientes asignados al analista
 * @param byStatus conteo por estado (clave = nombre del {@code CaseStatus}); sólo aparecen los
 *                 estados con al menos un expediente. El front deriva "pendientes de decisión",
 *                 "en trámite" (no finales) y "resueltos" (APPROVED + REJECTED) a partir de acá.
 * @param highRisk cantidad de esos expedientes con alerta de fraude alta o crítica (riskBand
 *                 HIGH o CRITICAL) — la tarjeta de "riesgo alto" del inicio.
 */
public record AssignedCaseSummaryResponse(long total, Map<String, Long> byStatus, long highRisk) {
}
