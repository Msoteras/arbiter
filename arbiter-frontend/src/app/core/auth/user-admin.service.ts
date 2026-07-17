import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { UserRole } from '../models/user-role';

export interface CreateUserRequest {
  email: string;
  nombre: string;
  apellido: string;
  password: string;
  rol: UserRole;
  sector: string;
  fechaIngreso?: string;
}

export interface UserResponse {
  id: number;
  email: string;
  nombre: string;
  apellido: string;
  rol: UserRole;
  sector: string;
  fechaIngreso: string | null;
  createdAt: string;
}

/** H0002/H0003 - Alta y listado de usuarios. Solo el referente puede llamar a estos endpoints (RBAC). */
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

  updateRole(id: number, rol: UserRole): Observable<UserResponse> {
    return this.http.put<UserResponse>(`${this.baseUrl}/${id}/role`, { rol });
  }
}
