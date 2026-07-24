import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { UserRole } from '../models/user-role';

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  expiresAt: string;
  email: string;
  rol: UserRole;
  nombre: string;
  apellido: string;
  /** DNI del asegurado (null para analista/referente). */
  insuredId: string | null;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/auth`;

  login(request: LoginRequest): Observable<LoginResponse> {
    return this.http.post<LoginResponse>(`${this.baseUrl}/login`, request);
  }

  /** Auth0 Phase 3: the invited user picks their own password here — only then do we create them in Auth0. */
  activate(token: string, password: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/activate`, { token, password });
  }

  /** Validates the token (invite or reset) without consuming it — doesn't change anything server-side. */
  checkToken(token: string): Observable<void> {
    return this.http.get<void>(`${this.baseUrl}/invite-tokens/${encodeURIComponent(token)}`);
  }

  /** Forgot-password: always responds 204, whether or not the email exists. */
  forgotPassword(email: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/forgot-password`, { email });
  }

  /** The user already exists in Auth0 — this only updates their password. */
  resetPassword(token: string, password: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/reset-password`, { token, password });
  }
}
