import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, map } from 'rxjs';

import { ExpedienteService } from '../../expedientes/expediente.service';

export type AttentionSeverity = 'risk' | 'warning' | 'info';

export interface AttentionItem {
  key: string;
  /** Ready-made text with the count inside: "2 expedientes sin movimiento hace +15 días". */
  title: string;
  detail: string;
  count: number;
  severity: AttentionSeverity;
  /** Inbox query params that show exactly these cases. */
  queryParams: Record<string, string>;
}

const STALE_DAYS = 15;

/** Case numbers named in the line before summarizing. */
const NAMED = 3;

/**
 * Deliberately asks the inbox, not reports-service: the inbox already filters actionable cases and is
 * what the user opens on click, so there's a single source for "what is stuck".
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
          item(
            'stale',
            stale,
            'risk',
            'sin movimiento hace más de 15 días',
            'pendientes de avanzar',
            {
              staleDays: String(STALE_DAYS),
              scope: 'OPEN',
            },
          ),
          item(
            'fraud',
            unreviewedFraud,
            'risk',
            'con alerta de fraude sin revisar',
            'esperando al analista',
            {
              fraudAlert: 'true',
              status: 'PENDING_ANALYST_REVIEW',
            },
          ),
          item(
            'docs',
            awaitingDocs,
            'warning',
            'con documentación faltante',
            'esperando al asegurado',
            {
              status: 'AWAITING_DOCUMENTATION',
            },
          ),
          item(
            'unassigned',
            unassigned,
            'info',
            'sin analista asignado',
            'nadie los tomó todavía',
            {
              unassigned: 'true',
              scope: 'OPEN',
            },
          ),
        ].filter((entry) => entry.count > 0),
      ),
    );
  }

  /** Fetches only the first rows: the count comes from `totalElements`, the rows only name cases. */
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
