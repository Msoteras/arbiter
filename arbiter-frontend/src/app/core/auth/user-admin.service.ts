import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { UserRole } from '../models/user-role';
import { UserStatus } from '../models/user-status';

export interface CreateUserRequest {
  email: string;
  nombre: string;
  apellido: string;
  rol: UserRole;
}

/**
 * Mirrors auth-service's AnalystResponse. `id` is the per-insurer-schema `claims_analyst` id:
 * never compare it across insurers or against the session user id.
 */
export interface AnalystResponse {
  id: number;
  nombre: string;
  apellido: string;
  email: string;
}

export interface UserResponse {
  id: number;
  email: string;
  nombre: string;
  apellido: string;
  rol: UserRole;
  estado: UserStatus;
  createdAt: string;
}

@Injectable({ providedIn: 'root' })
export class UserAdminService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/auth/users`;

  create(request: CreateUserRequest): Observable<UserResponse> {
    return this.http.post<UserResponse>(this.baseUrl, request);
  }

  list(): Observable<UserResponse[]> {
    return this.http.get<UserResponse[]>(this.baseUrl);
  }

  /** Unlike `list()`, analysts may call it too. The `id` is what `POST /cases/{id}/assign` expects. */
  listAnalysts(): Observable<AnalystResponse[]> {
    return this.http.get<AnalystResponse[]>(`${this.baseUrl}/analysts`);
  }

  updateRole(id: number, rol: UserRole): Observable<UserResponse> {
    return this.http.put<UserResponse>(`${this.baseUrl}/${id}/role`, { rol });
  }

  /** Permanent hard delete, not a deactivation. */
  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  /** Only makes sense for users in PENDING status — the backend rejects it if already active. */
  resendInvite(id: number): Observable<UserResponse> {
    return this.http.post<UserResponse>(`${this.baseUrl}/${id}/resend-invite`, {});
  }

  /**
   * Bulk-provisions insured users with an active policy. Returns 202 with no body: the run
   * continues in the background, so reload the list to see results. Idempotent (de-dup by email).
   */
  provisionInsured(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/insured/bulk-provision`, {});
  }
}
