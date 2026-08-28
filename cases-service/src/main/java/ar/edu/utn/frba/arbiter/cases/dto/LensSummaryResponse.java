package ar.edu.utn.frba.arbiter.cases.dto;

/**
 * Los conteos de las lentes de la bandeja, sobre los filtros vigentes. Van juntos porque la pantalla
 * los muestra juntos: pedirlos de a uno eran cinco requests por cada cambio de filtro, cada una
 * trayendo además una fila entera (con sus joins de análisis) solo para leerle el total.
 */
public record LensSummaryResponse(long all, long mine, long assigned, long unassigned, long fraud) {
}
