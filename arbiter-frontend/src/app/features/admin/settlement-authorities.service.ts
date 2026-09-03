import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/**
 * Hasta cuánto autoriza un analista por su cuenta en un ramo. `maxAmount` en null significa que
 * el ramo no tiene tope y el analista autoriza cualquier monto — es una respuesta, no un dato que
 * falta, y la pantalla lo dice con todas las letras en vez de dejar el campo vacío.
 */
export interface SettlementAuthority {
  branchId: number;
  branchName: string;
  maxAmount: number | null;
  updatedAt: string | null;
}

/** Una liquidación esperando la firma del referente. Calca PendingSettlementResponse. */
export interface PendingSettlement {
  caseId: number;
  insuredName: string | null;
  branch: string | null;
  claimCause: string | null;
  analystName: string | null;
  calculatedAmount: number;
  settledAmount: number;
  adjustmentReason: string | null;
  authorityLimit: number | null;
  /** Cuánto se pasa del tope, ya restado por el backend. */
  excess: number | null;
  confirmedAt: string;
  /** Días que lleva esperando. El siniestro consume su plazo legal mientras tanto. */
  waitingFor: number;
}

/**
 * Atribuciones de liquidación (Anexo II del procedimiento de la compañía): el tope por ramo que
 * configura el referente, y la cola de lo que lo superó.
 */
@Injectable({ providedIn: 'root' })
export class SettlementAuthoritiesService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  /** Todos los ramos, incluidos los que no tienen tope (llegan con `maxAmount: null`). */
  list(): Observable<SettlementAuthority[]> {
    return this.http.get<SettlementAuthority[]>(`${this.base}/settlement-authorities`);
  }

  /** `maxAmount` en null saca el tope: el ramo vuelve a que el analista autorice todo. */
  set(branchId: number, maxAmount: number | null): Observable<void> {
    return this.http.put<void>(`${this.base}/settlement-authorities/${branchId}`, { maxAmount });
  }

  pending(): Observable<PendingSettlement[]> {
    return this.http.get<PendingSettlement[]>(`${this.base}/cases/settlements/pending-authorization`);
  }

  authorize(caseId: number): Observable<unknown> {
    return this.http.post(`${this.base}/cases/${caseId}/settlement/authorize`, {});
  }

  returnToAnalyst(caseId: number, reason: string): Observable<unknown> {
    return this.http.post(`${this.base}/cases/${caseId}/settlement/return`, { reason });
  }
}
