import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

export interface BranchOption {
  id: number;
  name: string;
}

/** Global catalog shared by every insurer: creating or deleting a branch affects all of them. */
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
