import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of } from 'rxjs';

import { environment } from '../../../environments/environment';
import { CASE_DOCUMENT_TYPES, CaseDocumentType, documentTypeLabel } from '../../core/models/case-document';

/**
 * Agenda documental REQUERIDA por ramo + hecho generador, para el asegurado (arma el uploader) y el
 * analista (checklist de faltantes). Es la misma agenda que edita el referente en la pantalla de
 * reglas; acá se lee por NOMBRE de ramo y de hecho generador —lo único que la póliza y el expediente
 * tienen a mano; los ids numéricos solo los maneja el referente—. Si esa combinación no tiene agenda
 * configurada (o el backend está caído), se cae al catálogo completo (`CASE_DOCUMENT_TYPES`, mismo
 * vocabulario del referente) para no dejar al asegurado sin poder subir nada.
 */
@Injectable({ providedIn: 'root' })
export class DocumentAgendaService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules/document-requirements`;

  /** Códigos crudos de la agenda (lista vacía si no hay agenda configurada). */
  getForBranch(branch: string, claimCause: string): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/for-branch`, { params: { branch, claimCause } });
  }

  /**
   * La agenda como filas {type,label} listas para el uploader / checklist. Sin agenda o backend
   * caído ⇒ cae al catálogo completo, nunca deja la lista vacía.
   */
  slotsForBranch(branch: string, claimCause: string): Observable<readonly CaseDocumentType[]> {
    return this.getForBranch(branch, claimCause).pipe(
      map((codes) =>
        codes.length
          ? codes.map((type) => ({ type, label: documentTypeLabel(type) }))
          : CASE_DOCUMENT_TYPES,
      ),
      catchError(() => of(CASE_DOCUMENT_TYPES)),
    );
  }
}
