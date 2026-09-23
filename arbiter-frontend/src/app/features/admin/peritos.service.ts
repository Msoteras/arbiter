import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { ProviderType } from '../../core/models/peritaje';

/** Mirrors cases-service ExpertFirmResponse. */
export interface PeritoAdmin {
  id: number;
  name: string;
  email: string;
  zone: string | null;
  /** null = generalist: covers every branch. */
  branchId: number | null;
  branchName: string | null;
  active: boolean;
  providerType: ProviderType;
}

export interface PeritoRequest {
  name: string;
  email: string;
  zone: string | null;
  branchId: number | null;
  active: boolean;
  providerType: ProviderType;
}

/**
 * Expert firms catalog, owned by cases-service. The amount threshold that enables a referral is a
 * business rule in rules-service, not here.
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

  /** 409 if the firm already received referrals: those are deactivated, not deleted. */
  remove(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }
}
