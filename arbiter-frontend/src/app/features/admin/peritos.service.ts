import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/** Un perito del catálogo — espejo de ExpertFirmResponse de cases-service. */
export interface PeritoAdmin {
  id: number;
  name: string;
  email: string;
  zone: string | null;
  /** null = generalista: cubre todos los ramos. */
  branchId: number | null;
  branchName: string | null;
  active: boolean;
}

export interface PeritoRequest {
  name: string;
  email: string;
  zone: string | null;
  branchId: number | null;
  active: boolean;
}

/**
 * Catálogo de peritos de la aseguradora, contra cases-service (dueño de la tabla). Es lo que el
 * analista ve en el selector al derivar: sin peritos cargados para el ramo no hay a quién derivar,
 * por más que la regla de monto lo habilite.
 *
 * El umbral que habilita la derivación NO está acá: es una regla de negocio y vive en rules-service.
 */
@Injectable({ providedIn: 'root' })
export class PeritosService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/expert-firms`;

  list(): Observable<PeritoAdmin[]> {
    return this.http.get<PeritoAdmin[]>(this.base);
  }

  create(request: PeritoRequest): Observable<PeritoAdmin> {
    return this.http.post<PeritoAdmin>(this.base, request);
  }

  update(id: number, request: PeritoRequest): Observable<PeritoAdmin> {
    return this.http.put<PeritoAdmin>(`${this.base}/${id}`, request);
  }

  /** 409 si el perito ya recibió derivaciones: en ese caso se desactiva, no se borra. */
  remove(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }
}
