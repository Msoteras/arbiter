import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/** Los extremos que acepta el backend. Un objetivo de 0 días no se puede cumplir nunca. */
export const TARGET_DAYS_MIN = 1;
export const TARGET_DAYS_MAX = 365;

/**
 * El objetivo de resolución de la aseguradora: en cuántos días se propone cerrar un siniestro.
 *
 * No es el plazo legal — ese es por expediente y no lo fija nadie — sino una meta de gestión que
 * puede ser más exigente que la ley.
 */
export interface ResolutionTarget {
  enabled: boolean;
  /** Null mientras la aseguradora no haya fijado ninguno. */
  targetDays: number | null;
}

@Injectable({ providedIn: 'root' })
export class ResolutionTargetService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules/resolution-target`;

  get(): Observable<ResolutionTarget> {
    return this.http.get<ResolutionTarget>(this.base);
  }

  save(target: ResolutionTarget): Observable<ResolutionTarget> {
    return this.http.put<ResolutionTarget>(this.base, target);
  }
}
