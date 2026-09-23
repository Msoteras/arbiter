import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { environment } from '../../../environments/environment';
import { InsuredProfile, OnboardingRequest, UpdateProfileRequest } from '../models/profile';
import { LoginResponse } from './auth.service';
import { AuthSessionService } from './auth-session.service';

/**
 * Onboarding and PATCH return a new JWT (it carries `onboardingComplete`), so the session is
 * replaced here; otherwise the guard would keep reading the stale claim.
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
