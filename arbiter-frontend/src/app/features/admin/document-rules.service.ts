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
 * Agenda documental de un ramo (solapa Documentación) contra rules-service. La pantalla la edita
 * como lista plana por ramo; el backend hace fan-out a cada hecho generador del ramo
 * (docs/decisiones-reglas-a-validar.md, D5) — invisible para el frontend.
 */
@Injectable({ providedIn: 'root' })
export class DocumentRulesService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules/document-requirements`;

  get(branchId: number): Observable<string[]> {
    return this.http.get<string[]>(this.base, { params: { branchId: String(branchId) } });
  }

  save(branchId: number, documentTypes: string[]): Observable<DocumentRequirementDto[]> {
    return this.http.put<DocumentRequirementDto[]>(this.base, documentTypes, {
      params: { branchId: String(branchId) },
    });
  }
}
