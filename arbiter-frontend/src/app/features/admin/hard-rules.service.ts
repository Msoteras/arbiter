import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/** Coverage-scoped hard temporal rule types. Mirrors common-lib's `RuleType.coverageScoped()`. */
export type HardRuleType = 'WAITING_PERIOD' | 'REPORT_DEADLINE' | 'POLICE_DEADLINE' | 'MAX_EVENTS_YEAR';

/**
 * A hard rule as the referente edits it: whether it's active and — only for POLICE_DEADLINE — its
 * threshold in hours. The rest of the thresholds don't live here: they're terms of the contract,
 * edited above in the coverage's own fields (waiting period, report deadline, events cap).
 */
export interface HardRule {
  ruleType: HardRuleType;
  enabled: boolean;
  deadlineHours: number | null;
}

/** Spanish labels — the backend sends English literals, translating them is the frontend's job. */
export const HARD_RULE_LABELS: Record<HardRuleType, string> = {
  WAITING_PERIOD: 'Carencia',
  REPORT_DEADLINE: 'Plazo de denuncia',
  POLICE_DEADLINE: 'Plazo de la denuncia policial',
  MAX_EVENTS_YEAR: 'Tope de eventos por año',
};

/** Insurer-scoped hard temporal rule types. Mirrors common-lib's `RuleType.insurerScoped()`. */
export type InsurerHardRuleType = 'POLICY_IN_FORCE' | 'POLICY_STANDING';

/** `onArrears`, only meaningful for POLICY_STANDING. */
export type OnArrears = 'REJECT' | 'STANDBY';

/**
 * A Hard Stop rule as the referente edits it: whether it's active and — only for POLICY_STANDING —
 * what happens when a policy is found in arrears at intake.
 */
export interface InsurerHardRule {
  ruleType: InsurerHardRuleType;
  enabled: boolean;
  onArrears: OnArrears | null;
}

export const INSURER_HARD_RULE_LABELS: Record<InsurerHardRuleType, string> = {
  POLICY_IN_FORCE: 'Vigencia de la póliza',
  POLICY_STANDING: 'Mora de la póliza',
};

/**
 * Hard temporal rules against rules-service: which rules the engine evaluates, and with what
 * deadline for the police report. Each is a row of `insurer_rule`, which is what lets them be
 * audited in `rule_result` and changed without a redeploy — the police deadline used to be a fixed
 * 72h constant, the same for every insurer.
 *
 * <p>Coverage-scoped (waiting period, deadlines, events cap) and insurer-scoped (coverage window,
 * arrears — the Hard Stop tab) are separate endpoints: the insurer-scoped ones apply the same way
 * no matter which coverage the claim lands under, so there's nothing to key them by.
 */
@Injectable({ providedIn: 'root' })
export class HardRulesService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules`;

  /** Always returns all four: the one the insurer didn't configure comes back disabled. */
  get(branchId: number, coverageId: number): Observable<HardRule[]> {
    return this.http.get<HardRule[]>(`${this.base}/hard-rules`, {
      params: { branchId: String(branchId), coverageId: String(coverageId) },
    });
  }

  save(branchId: number, coverageId: number, rules: HardRule[]): Observable<HardRule[]> {
    return this.http.put<HardRule[]>(`${this.base}/hard-rules`, rules, {
      params: { branchId: String(branchId), coverageId: String(coverageId) },
    });
  }

  /** Always returns both: the one the insurer didn't configure comes back disabled. */
  getInsurerWide(): Observable<InsurerHardRule[]> {
    return this.http.get<InsurerHardRule[]>(`${this.base}/insurer-hard-rules`);
  }

  saveInsurerWide(rules: InsurerHardRule[]): Observable<InsurerHardRule[]> {
    return this.http.put<InsurerHardRule[]>(`${this.base}/insurer-hard-rules`, rules);
  }
}
