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
 * Catálogo real de ramos (branch) contra rules-service — reemplaza al mock RulesConfigService para
 * la lista de la pantalla de reglas. No hay CRUD de Branch: el catálogo lo fija el seed, así que
 * esto es solo lectura (la lista y los nombres salen de la base, no del front).
 */
@Injectable({ providedIn: 'root' })
export class BranchesService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules/branches`;

  list(): Observable<BranchOption[]> {
    return this.http.get<BranchOption[]>(this.base);
  }
}
