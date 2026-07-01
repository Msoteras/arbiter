import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { ExpedienteResponse } from '../../core/models/expediente';

@Injectable({ providedIn: 'root' })
export class ExpedienteService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/cases`;

  getById(id: string | number): Observable<ExpedienteResponse> {
    return this.http.get<ExpedienteResponse>(`${this.baseUrl}/${id}`);
  }
}
