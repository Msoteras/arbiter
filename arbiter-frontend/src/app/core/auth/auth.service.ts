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
  /** Whether the insured already went through first-time onboarding. Null for other roles. */
  onboardingComplete: boolean | null;
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

  /**
   * The invited user picks their password here, and only then are they created in Auth0. Returns
   * an issued session (same shape as `login()`) so the caller can start it right away.
   */
  activate(token: string, password: string): Observable<LoginResponse> {
    return this.sealed(password).pipe(
      switchMap((sealedPassword) =>
        this.http.post<LoginResponse>(`${this.baseUrl}/activate`, {
          token,
          password: sealedPassword,
        }),
      ),
    );
  }

  /** Validates an invite or reset token without consuming it. */
  checkToken(token: string): Observable<void> {
    return this.http.get<void>(`${this.baseUrl}/invite-tokens/${encodeURIComponent(token)}`);
  }

  /** Forgot-password: always responds 204, whether or not the email exists. */
  forgotPassword(email: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/forgot-password`, { email });
  }

  /** Updates an existing Auth0 user's password; like `activate()`, returns an issued session. */
  resetPassword(token: string, password: string): Observable<LoginResponse> {
    return this.sealed(password).pipe(
      switchMap((sealedPassword) =>
        this.http.post<LoginResponse>(`${this.baseUrl}/reset-password`, {
          token,
          password: sealedPassword,
        }),
      ),
    );
  }

  /**
   * Seals the password with the backend's public key so it is never readable in the request body.
   * The key is fetched every time so a restart that rotates it never leaves a stale one cached.
   */
  private sealed(password: string): Observable<string> {
    return this.http
      .get<PublicKeyResponse>(`${this.baseUrl}/public-key`)
      .pipe(switchMap(({ publicKey }) => from(sealPassword(password, publicKey))));
  }
}
