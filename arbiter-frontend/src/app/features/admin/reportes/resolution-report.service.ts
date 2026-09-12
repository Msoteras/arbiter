import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { ReportFormat, ResolutionReport, ResolutionReportParams } from './resolution-report';

export interface ReportFile {
  blob: Blob;
  filename: string;
}

/** reports-service — resolution report (GET /api/v1/reports/resolutions). */
@Injectable({ providedIn: 'root' })
export class ResolutionReportService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/reports/resolutions`;

  preview(params: ResolutionReportParams): Observable<ResolutionReport> {
    return this.http.get<ResolutionReport>(this.baseUrl, { params: toHttpParams(params) });
  }

  /**
   * Through HttpClient and not an <a href>: the endpoint requires the JWT, and only requests made
   * by HttpClient go through the authInterceptor — a plain browser navigation would come back 401.
   */
  export(params: ResolutionReportParams, format: ReportFormat): Observable<ReportFile> {
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

function toHttpParams({ from, to, claimCause }: ResolutionReportParams): HttpParams {
  const params = new HttpParams().set('from', from).set('to', to);
  return claimCause ? params.set('claimCause', claimCause) : params;
}

/** The backend names the file (it carries the period); the fallback only covers a missing header. */
function filenameFrom(response: HttpResponse<Blob>, format: ReportFormat): string {
  const disposition = response.headers.get('Content-Disposition') ?? '';
  const match = /filename="?([^";]+)"?/.exec(disposition);
  return match?.[1] ?? `resoluciones.${format.toLowerCase()}`;
}
