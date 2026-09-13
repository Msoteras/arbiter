package ar.edu.utn.frba.arbiter.cases.dto;

/**
 * Los conteos de las lentes de la bandeja, sobre los filtros vigentes. Van juntos porque la pantalla
 * los muestra juntos: pedirlos de a uno eran cinco requests por cada cambio de filtro, cada una
 * trayendo además una fila entera (con sus joins de análisis) solo para leerle el total.
 *
 * <p>{@code open}/{@code closed} no dependen de qué pestaña esté activa: son el corte por ciclo de
 * vida sobre los mismos filtros base, igual que {@code all}.
 */
public record LensSummaryResponse(
        long all, long mine, long assigned, long unassigned, long fraud, long open, long closed) {
}
