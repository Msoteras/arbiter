import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { ClaimMetrics, MetricsFilter, MetricsRange } from './claim-metrics';

/** El período: uno de los atajos, o un rango a medida. Nunca los dos — el backend lo rechaza. */
export type MetricsPeriod = { range: MetricsRange } | { from: string; to: string };

/**
 * Los indicadores de gestión de la aseguradora, contra reports-service.
 *
 * Una sola llamada trae todo el tablero. La aseguradora no se manda: el backend la deduce del
 * token, así que no hay forma de pedir la cartera de otra compañía.
 */
@Injectable({ providedIn: 'root' })
export class ClaimMetricsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/reports/metrics`;

  load(period: MetricsPeriod, filter: MetricsFilter): Observable<ClaimMetrics> {
    let params = new HttpParams();
    if ('range' in period) {
      params = params.set('range', period.range);
    } else {
      params = params.set('from', period.from).set('to', period.to);
    }
    if (filter.branchId !== null) {
      params = params.set('branchId', String(filter.branchId));
    }
    if (filter.analystId !== null) {
      params = params.set('analystId', String(filter.analystId));
    }
    return this.http.get<ClaimMetrics>(this.base, { params });
  }
}
