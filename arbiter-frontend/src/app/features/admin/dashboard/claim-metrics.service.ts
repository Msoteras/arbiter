import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { ComparisonChoice, withComparison } from '../../../core/models/comparison';
import { ClaimMetrics, MetricsFilter, MetricsRange } from './claim-metrics';

/** A preset range or a custom one, never both: the backend rejects it. */
export type MetricsPeriod = { range: MetricsRange } | { from: string; to: string };

/** The insurer isn't sent: the backend takes it from the token. */
@Injectable({ providedIn: 'root' })
export class ClaimMetricsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/reports/metrics`;

  load(
    period: MetricsPeriod,
    filter: MetricsFilter,
    comparison: ComparisonChoice,
  ): Observable<ClaimMetrics> {
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
    return this.http.get<ClaimMetrics>(this.base, { params: withComparison(params, comparison) });
  }

  /** Referente only. `to` must be before today: only closed days are stored. */
  recalculate(from: string, to: string): Observable<unknown> {
    const params = new HttpParams().set('from', from).set('to', to);
    return this.http.post(`${this.base}/recalculations`, null, { params });
  }
}
