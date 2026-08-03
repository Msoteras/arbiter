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
  estado: UserStatus;
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

  /**
   * Analistas a los que se les puede asignar un expediente, ordenados por apellido. A diferencia
   * de `list()`, también lo puede llamar un analista: asignar es acción de los dos roles
   * operativos. Alimenta el selector de asignación de la bandeja y del detalle.
   */
  listAnalysts(): Observable<UserResponse[]> {
    return this.http.get<UserResponse[]>(`${this.baseUrl}/analysts`);
  }

  updateRole(id: number, rol: UserRole): Observable<UserResponse> {
    return this.http.put<UserResponse>(`${this.baseUrl}/${id}/role`, { rol });
  }

  /** Borrado definitivo e irreversible (wireframe "Eliminar") — no es una baja/desactivación. */
  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  /** Only makes sense for users in PENDING status — the backend rejects it if already active. */
  resendInvite(id: number): Observable<UserResponse> {
    return this.http.post<UserResponse>(`${this.baseUrl}/${id}/resend-invite`, {});
  }
}
