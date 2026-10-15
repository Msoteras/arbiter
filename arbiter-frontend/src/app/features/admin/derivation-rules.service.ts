import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/** Mirrors rules-service ExpertDerivationRuleDto. The amount travels even with the rule off. */
export interface ExpertDerivationRule {
  enabled: boolean;
  /** Claimed amount the derivation applies from; null on a branch that never configured it. */
  minClaimedAmount: number | null;
}

/** Mirrors rules-service RepairDerivationRuleDto. The claim causes travel even with the rule off. */
export interface RepairDerivationRule {
  enabled: boolean;
  claimCauseIds: number[];
}

/**
 * Per-branch derivation rules: whether the analyst may send a case to an expert or to a repair
 * shop. They enable the option; deriving stays the analyst's call.
 */
@Injectable({ providedIn: 'root' })
export class DerivationRulesService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules`;

  getExpert(branchId: number): Observable<ExpertDerivationRule> {
    return this.http.get<ExpertDerivationRule>(`${this.base}/expert-derivation`, {
      params: { branchId: String(branchId) },
    });
  }

  saveExpert(branchId: number, rule: ExpertDerivationRule): Observable<ExpertDerivationRule> {
    return this.http.put<ExpertDerivationRule>(`${this.base}/expert-derivation`, rule, {
      params: { branchId: String(branchId) },
    });
  }

  getRepair(branchId: number): Observable<RepairDerivationRule> {
    return this.http.get<RepairDerivationRule>(`${this.base}/repair-derivation`, {
      params: { branchId: String(branchId) },
    });
  }

  saveRepair(branchId: number, rule: RepairDerivationRule): Observable<RepairDerivationRule> {
    return this.http.put<RepairDerivationRule>(`${this.base}/repair-derivation`, rule, {
      params: { branchId: String(branchId) },
    });
  }
}
