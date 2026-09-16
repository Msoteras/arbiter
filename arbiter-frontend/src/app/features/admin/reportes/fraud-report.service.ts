import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { FraudReport, FraudReportParams } from './fraud-report';
import { ReportFile } from './resolution-report.service';
import { ReportFormat } from './resolution-report';

/** reports-service — reporte de detección de fraude (GET /api/v1/reports/fraud). */
@Injectable({ providedIn: 'root' })
export class FraudReportService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/reports/fraud`;

  report(params: FraudReportParams): Observable<FraudReport> {
    return this.http.get<FraudReport>(this.baseUrl, { params: toHttpParams(params) });
  }

  /**
   * Por HttpClient y no por un <a href>: el endpoint exige el JWT, y sólo los pedidos que hace
   * HttpClient pasan por el authInterceptor — una navegación del navegador volvería 401.
   */
  export(params: FraudReportParams, format: ReportFormat): Observable<ReportFile> {
    return this.http
      .get(`${this.baseUrl}/export`, {
        params: toHttpParams(params).set('format', format),
        responseType: 'blob',
        observe: 'response',
      })
      .pipe(
        map((response) => ({
          blob: response.body ?? new Blob(),
          filename: filenameFrom(response, format),
        })),
      );
  }
}

function toHttpParams({ from, to, branchId, riskBand }: FraudReportParams): HttpParams {
  let params = new HttpParams().set('from', from).set('to', to);
  if (branchId !== null) {
    params = params.set('branchId', String(branchId));
  }
  return riskBand ? params.set('riskBand', riskBand) : params;
}

/** El backend nombra el archivo (lleva el período); el fallback sólo cubre un header ausente. */
function filenameFrom(response: HttpResponse<Blob>, format: ReportFormat): string {
  const disposition = response.headers.get('Content-Disposition') ?? '';
  const match = /filename="?([^";]+)"?/.exec(disposition);
  return match?.[1] ?? `fraude.${format.toLowerCase()}`;
}
