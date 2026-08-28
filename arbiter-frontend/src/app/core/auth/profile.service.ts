import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { environment } from '../../../environments/environment';
import {
  InsuredProfile,
  OnboardingRequest,
  UpdateProfileRequest,
} from '../models/profile';
import { LoginResponse } from './auth.service';
import { AuthSessionService } from './auth-session.service';

/**
 * Perfil del asegurado (H0009 — primer ingreso y "Mi perfil").
 *
 * Onboarding y PATCH devuelven un `LoginResponse` con un JWT nuevo, no un perfil: el token
 * lleva el claim `onboardingComplete`, así que después de completarlo hay que reemplazar la
 * sesión o el guard seguiría leyendo el claim viejo y rebotando al onboarding para siempre.
 * Ese reemplazo se hace acá y no en cada componente, para que no se pueda olvidar.
 */
@Injectable({ providedIn: 'root' })
export class ProfileService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(AuthSessionService);
  private readonly baseUrl = `${environment.apiBaseUrl}/auth/profile`;

  get(): Observable<InsuredProfile> {
    return this.http.get<InsuredProfile>(this.baseUrl);
  }

  completeOnboarding(request: OnboardingRequest): Observable<LoginResponse> {
    return this.http
      .post<LoginResponse>(`${this.baseUrl}/onboarding`, request)
      .pipe(tap((response) => this.session.start(response)));
  }

  update(request: UpdateProfileRequest): Observable<LoginResponse> {
    return this.http
      .patch<LoginResponse>(this.baseUrl, request)
      .pipe(tap((response) => this.session.start(response)));
  }
}
