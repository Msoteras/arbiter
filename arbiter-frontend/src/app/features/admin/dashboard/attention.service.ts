import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, map } from 'rxjs';

import { ExpedienteService } from '../../expedientes/expediente.service';

/** Cuán urgente es un ítem del panel. Mapea a los tonos del semáforo en la pantalla. */
export type AttentionSeverity = 'risk' | 'warning' | 'info';

/** Un renglón de "Requiere atención": qué pasa, cuántos, y cómo llegar a ellos. */
export interface AttentionItem {
  key: string;
  /** El texto ya armado, con el número adentro: "2 expedientes sin movimiento hace +15 días". */
  title: string;
  detail: string;
  count: number;
  severity: AttentionSeverity;
  /** Parámetros de la bandeja que muestran exactamente estos expedientes. */
  queryParams: Record<string, string>;
}

/** Sin movimiento hace más de esto, un expediente abierto está frenado. */
const STALE_DAYS = 15;

/** Cuántos números de expediente se nombran en el renglón antes de resumir. */
const NAMED = 3;

/**
 * El panel "Requiere atención" del tablero: qué expedientes están frenados, esperando, sin revisar
 * o sin dueño.
 *
 * <p>No consulta a reports-service: pregunta a la bandeja, que es la que ya sabe filtrar
 * expedientes accionables y la que el usuario abre al hacer clic. Calcularlo aparte en el módulo de
 * reportes daría dos lugares respondiendo "qué está frenado", y el día que discrepen nadie va a
 * saber cuál creer.
 */
@Injectable({ providedIn: 'root' })
export class AttentionService {
  private readonly expedientes = inject(ExpedienteService);

  load(): Observable<AttentionItem[]> {
    return forkJoin({
      stale: this.probe({ staleDays: STALE_DAYS, scope: 'OPEN' }),
      awaitingDocs: this.probe({ status: ['AWAITING_DOCUMENTATION'] }),
      unreviewedFraud: this.probe({ fraudAlert: true, status: ['PENDING_ANALYST_REVIEW'] }),
      unassigned: this.probe({ unassigned: true, scope: 'OPEN' }),
    }).pipe(
      map(({ stale, awaitingDocs, unreviewedFraud, unassigned }) =>
        [
          item('stale', stale, 'risk', 'sin movimiento hace más de 15 días', 'pendientes de avanzar', {
            staleDays: String(STALE_DAYS),
            scope: 'OPEN',
          }),
          item('fraud', unreviewedFraud, 'risk', 'con alerta de fraude sin revisar', 'esperando al analista', {
            fraudAlert: 'true',
            status: 'PENDING_ANALYST_REVIEW',
          }),
          item('docs', awaitingDocs, 'warning', 'con documentación faltante', 'esperando al asegurado', {
            status: 'AWAITING_DOCUMENTATION',
          }),
          item('unassigned', unassigned, 'info', 'sin analista asignado', 'nadie los tomó todavía', {
            unassigned: 'true',
            scope: 'OPEN',
          }),
        ].filter((entry) => entry.count > 0),
      ),
    );
  }

  /**
   * Pide apenas las primeras filas: del total se lee `totalElements`, y de las filas solo los
   * números de expediente que el renglón nombra. Traer la página entera sería cargar la bandeja
   * cuatro veces para mostrar cuatro líneas de texto.
   */
  private probe(
    params: Parameters<ExpedienteService['list']>[0],
  ): Observable<{ total: number; ids: number[] }> {
    return this.expedientes
      .list({ ...params, size: NAMED, sort: 'updatedAt,asc' })
      .pipe(map((page) => ({ total: page.totalElements, ids: page.content.map((row) => row.id) })));
  }
}

function item(
  key: string,
  probe: { total: number; ids: number[] },
  severity: AttentionSeverity,
  what: string,
  detail: string,
  queryParams: Record<string, string>,
): AttentionItem {
  const noun = probe.total === 1 ? 'expediente' : 'expedientes';
  const named = probe.ids.map((id) => `EXP-${id}`).join(' · ');
  const rest = probe.total - probe.ids.length;
  return {
    key,
    title: `${probe.total} ${noun} ${what}`,
    detail: rest > 0 ? `${named} y ${rest} más — ${detail}` : `${named} — ${detail}`,
    count: probe.total,
    severity,
    queryParams,
  };
}
