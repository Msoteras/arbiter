import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/** Confirmación de guardado: la fila de insurer_rule + su contenido, tal como quedó en la DB. */
export interface RuleTextResponse {
  id: number;
  branchId: number;
  ruleType: string;
  items: string[];
}

/**
 * Exclusiones comunes (solapa Coberturas) y reglas de negocio en texto libre (solapa Reglas de
 * negocio) contra rules-service. Sin tabla propia en el DER — el backend las persiste como
 * InsurerRule con rule_type de texto (docs/decisiones-reglas-a-validar.md, D3).
 */
@Injectable({ providedIn: 'root' })
export class BusinessRulesTextService {
  private readonly http = inject(HttpClient);
  private readonly exclusionsBase = `${environment.apiBaseUrl}/rules/exclusions`;
  private readonly businessRulesBase = `${environment.apiBaseUrl}/rules/business-rules`;

  getExclusions(branchId: number): Observable<string[]> {
    return this.http.get<string[]>(this.exclusionsBase, { params: { branchId: String(branchId) } });
  }

  saveExclusions(branchId: number, items: string[]): Observable<RuleTextResponse> {
    return this.http.put<RuleTextResponse>(this.exclusionsBase, items, {
      params: { branchId: String(branchId) },
    });
  }

  getBusinessRules(branchId: number): Observable<string[]> {
    return this.http.get<string[]>(this.businessRulesBase, { params: { branchId: String(branchId) } });
  }

  saveBusinessRules(branchId: number, items: string[]): Observable<RuleTextResponse> {
    return this.http.put<RuleTextResponse>(this.businessRulesBase, items, {
      params: { branchId: String(branchId) },
    });
  }
}
