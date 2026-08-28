import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/** Un ramo del catálogo real (tabla branch), tal como lo sirve rules-service. */
export interface BranchOption {
  id: number;
  name: string;
}

/**
 * Catálogo real de ramos (branch) contra rules-service — reemplaza al mock RulesConfigService. La
 * lista y los nombres salen de la base, y el ABM administra el catálogo global (branch es compartido
 * por todas las aseguradoras: crear/borrar un ramo lo agrega/saca del catálogo maestro).
 */
@Injectable({ providedIn: 'root' })
export class BranchesService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules/branches`;

  list(): Observable<BranchOption[]> {
    return this.http.get<BranchOption[]>(this.base);
  }

  create(name: string): Observable<BranchOption> {
    return this.http.post<BranchOption>(this.base, { name });
  }

  rename(id: number, name: string): Observable<BranchOption> {
    return this.http.put<BranchOption>(`${this.base}/${id}`, { name });
  }

  remove(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }
}
