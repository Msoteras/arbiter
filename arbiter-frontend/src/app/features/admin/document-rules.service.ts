import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/** Una fila persistida de la agenda documental — confirmación de lo que quedó en la DB. */
export interface DocumentRequirementDto {
  id: number;
  documentType: string;
  claimCauseId: number;
  mandatory: boolean;
}

/**
 * Agenda documental de un hecho generador de un ramo (solapa Documentación) contra rules-service.
 * Desde D5 (docs/decisiones-reglas-a-validar.md) la pantalla edita por hecho generador — ya no hay
 * fan-out a los demás hechos generadores del ramo.
 */
@Injectable({ providedIn: 'root' })
export class DocumentRulesService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules/document-requirements`;

  get(branchId: number, claimCauseId: number): Observable<string[]> {
    return this.http.get<string[]>(this.base, {
      params: { branchId: String(branchId), claimCauseId: String(claimCauseId) },
    });
  }

  save(branchId: number, claimCauseId: number, documentTypes: string[]): Observable<DocumentRequirementDto[]> {
    return this.http.put<DocumentRequirementDto[]>(this.base, documentTypes, {
      params: { branchId: String(branchId), claimCauseId: String(claimCauseId) },
    });
  }
}
