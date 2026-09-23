import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

export interface RuleTextResponse {
  id: number;
  branchId: number;
  ruleType: string;
  items: string[];
}

/**
 * Free-text exclusions and business rules. They have no table of their own: the backend stores them
 * as InsurerRule rows with a text rule_type.
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
    return this.http.get<string[]>(this.businessRulesBase, {
      params: { branchId: String(branchId) },
    });
  }

  saveBusinessRules(branchId: number, items: string[]): Observable<RuleTextResponse> {
    return this.http.put<RuleTextResponse>(this.businessRulesBase, items, {
      params: { branchId: String(branchId) },
    });
  }
}
