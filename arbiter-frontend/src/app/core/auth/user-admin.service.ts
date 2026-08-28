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
 * Espejo de `AnalystResponse` del auth-service: un analista asignable de la aseguradora actual.
 *
 * Es más chico que `UserResponse` a propósito — responde "a quién le puedo dar este expediente",
 * no "qué cuentas hay". El `id` es el de `claims_analyst`, que vive en el esquema de cada
 * aseguradora: **no lo compares entre aseguradoras ni contra el id de usuario de la sesión.**
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
   *
   * Vienen ya acotados a la aseguradora del usuario logueado (la tabla es por esquema), y el
   * `id` es el que espera `POST /cases/{id}/assign`.
   */
  listAnalysts(): Observable<AnalystResponse[]> {
    return this.http.get<AnalystResponse[]>(`${this.baseUrl}/analysts`);
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

  /**
   * Da de alta en bloque a los asegurados con póliza vigente de la aseguradora del referente,
   * leídos de la BD de la compañía, y les manda la invitación para elegir contraseña.
   *
   * Responde **202 sin cuerpo**: la corrida sigue en segundo plano y va creando cuentas y mandando
   * mails de a poco, así que no hay un total que devolver en el momento. Por eso esto no resuelve
   * con un resumen — para ver el resultado hay que recargar el listado.
   *
   * Es idempotente: a quien ya tiene cuenta no se la duplica (se lo reconoce por email), solo se le
   * vincula esta aseguradora.
   */
  provisionInsured(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/insured/bulk-provision`, {});
  }
}
