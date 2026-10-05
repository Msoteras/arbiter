import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { ProviderType, RamoRef } from '../../core/models/derivacion';

/** Mirrors cases-service ServiceProviderResponse. */
export interface ProveedorAdmin {
  id: number;
  name: string;
  email: string;
  zone: string | null;
  /** Empty = generalist: covers every branch, including the ones added later. */
  branches: RamoRef[];
  active: boolean;
  providerType: ProviderType;
}

export interface ProveedorRequest {
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
export class ProveedoresService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/expert-firms`;

  list(): Observable<ProveedorAdmin[]> {
    return this.http.get<ProveedorAdmin[]>(this.base);
  }

  create(request: ProveedorRequest): Observable<ProveedorAdmin> {
    return this.http.post<ProveedorAdmin>(this.base, request);
  }

  update(id: number, request: ProveedorRequest): Observable<ProveedorAdmin> {
    return this.http.put<ProveedorAdmin>(`${this.base}/${id}`, request);
  }

  /** 409 if the provider already received referrals: those are deactivated, not deleted. */
  remove(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }
}
