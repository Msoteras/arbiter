import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { ProviderType, BranchRef } from '../../core/models/derivation';

/** Mirrors cases-service ServiceProviderResponse. */
export interface ProviderAdmin {
  id: number;
  name: string;
  email: string;
  zone: string | null;
  /** Empty = generalist: covers every branch, including the ones added later. */
  branches: BranchRef[];
  active: boolean;
  providerType: ProviderType;
}

export interface ProviderRequest {
  name: string;
  email: string;
  zone: string | null;
  branchIds: number[];
  active: boolean;
  providerType: ProviderType;
}

/**
 * External providers catalog (loss adjusters and repair shops), owned by cases-service. The amount
 * threshold that enables a referral is a business rule in rules-service, not here.
 */
@Injectable({ providedIn: 'root' })
export class ProvidersService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/expert-firms`;

  list(): Observable<ProviderAdmin[]> {
    return this.http.get<ProviderAdmin[]>(this.base);
  }

  create(request: ProviderRequest): Observable<ProviderAdmin> {
    return this.http.post<ProviderAdmin>(this.base, request);
  }

  update(id: number, request: ProviderRequest): Observable<ProviderAdmin> {
    return this.http.put<ProviderAdmin>(`${this.base}/${id}`, request);
  }

  /** 409 if the provider already received referrals: those are deactivated, not deleted. */
  remove(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }
}
