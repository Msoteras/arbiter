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

  /** Fase 3 Auth0: el usuario invitado elige su contraseña acá — recién ahí se crea en Auth0. */
  activate(token: string, password: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/activate`, { token, password });
  }

  /** Valida el token (de invitación o de reset) sin consumirlo — no altera nada en el backend. */
  checkToken(token: string): Observable<void> {
    return this.http.get<void>(`${this.baseUrl}/invite-tokens/${encodeURIComponent(token)}`);
  }

  /** "Olvidé mi contraseña": responde 204 siempre, exista o no el email. */
  forgotPassword(email: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/forgot-password`, { email });
  }

  /** El usuario ya existe en Auth0 — acá solo se le actualiza la contraseña. */
  resetPassword(token: string, password: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/reset-password`, { token, password });
  }
}
