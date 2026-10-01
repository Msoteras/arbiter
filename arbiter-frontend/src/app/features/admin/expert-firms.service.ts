import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { ProviderType } from '../../core/models/expert-assessment';

/** Mirrors cases-service ExpertFirmResponse. */
export interface ExpertFirmAdmin {
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

export interface ExpertFirmRequest {
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
export class ExpertFirmsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/expert-firms`;

  list(): Observable<ExpertFirmAdmin[]> {
    return this.http.get<ExpertFirmAdmin[]>(this.base);
  }

  create(request: ExpertFirmRequest): Observable<ExpertFirmAdmin> {
    return this.http.post<ExpertFirmAdmin>(this.base, request);
  }

  update(id: number, request: ExpertFirmRequest): Observable<ExpertFirmAdmin> {
    return this.http.put<ExpertFirmAdmin>(`${this.base}/${id}`, request);
  }

  /** 409 if the firm already received referrals: those are deactivated, not deleted. */
  remove(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }
}
