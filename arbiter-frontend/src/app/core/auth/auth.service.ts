import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, from, switchMap } from 'rxjs';

import { environment } from '../../../environments/environment';
import { UserRole } from '../models/user-role';
import { sealPassword } from './password-cipher';

export interface LoginRequest {
  email: string;
  password: string;
}

interface PublicKeyResponse {
  publicKey: string;
  algorithm: string;
}

export interface LoginResponse {
  token: string;
  expiresAt: string;
  /** Id of the logged-in user — the bandeja resolves which cases are "mine" with it. */
  id: number;
  email: string;
  rol: UserRole;
  nombre: string;
  apellido: string;
  /** The insured's DNI (null for analista/referente). */
  insuredId: string | null;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/auth`;

  login(request: LoginRequest): Observable<LoginResponse> {
    return this.sealed(request.password).pipe(
      switchMap((password) =>
        this.http.post<LoginResponse>(`${this.baseUrl}/login`, { email: request.email, password }),
      ),
    );
  }

  /** Auth0 Phase 3: the invited user picks their own password here — only then do we create them in Auth0. */
  activate(token: string, password: string): Observable<void> {
    return this.sealed(password).pipe(
      switchMap((sealedPassword) =>
        this.http.post<void>(`${this.baseUrl}/activate`, { token, password: sealedPassword }),
      ),
    );
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
    return this.sealed(password).pipe(
      switchMap((sealedPassword) =>
        this.http.post<void>(`${this.baseUrl}/reset-password`, { token, password: sealedPassword }),
      ),
    );
  }

  /**
   * Seals the password with the backend's public key so it never shows up readable in the body —
   * not in devtools, a HAR, or an access log. Every endpoint that takes a password goes through
   * here. The key is fetched each time: one extra request, but no stale key cached after a restart
   * rotates it.
   */
  private sealed(password: string): Observable<string> {
    return this.http
      .get<PublicKeyResponse>(`${this.baseUrl}/public-key`)
      .pipe(switchMap(({ publicKey }) => from(sealPassword(password, publicKey))));
  }
}
