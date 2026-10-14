import { EffectRef, Injectable, effect, inject, untracked } from '@angular/core';

import { AuthSessionService } from '../../core/auth/auth-session.service';
import { ProfileService } from '../../core/auth/profile.service';
import { UserAdminService } from '../../core/auth/user-admin.service';
import { BranchesService } from '../admin/branches.service';
import { CoveragesRulesService } from '../admin/coverages-rules.service';
import { FraudRuleService } from '../admin/fraud-rule.service';
import { ProvidersService } from '../admin/providers.service';
import { ResolutionTargetService } from '../admin/resolution-target.service';
import { ScoringRulesService } from '../admin/scoring-rules.service';
import { SettlementAuthoritiesService } from '../admin/settlement-authorities.service';
import { CaseListParams, CaseService } from '../cases/case.service';
import { PolicyService } from '../cases/policy.service';

/** What the inbox asks for with no filters: lifecycle "open", newest first, first page. */
export const INBOX_FIRST_PAGE: CaseListParams = {
  scope: 'OPEN',
  sort: 'id,desc',
  page: 0,
  size: 10,
};

/** "Mis expedientes" with no filters. */
export const MY_CASES_FIRST_PAGE = { page: 0, size: 10 };

/**
 * Warms the screens each role opens most, while the user looks at the home screen. On Railway it
 * also wakes the sleeping modules before anyone clicks. Never the dashboard or reports: those are
 * heavy, and reports writes its daily totals when asked.
 */
@Injectable({ providedIn: 'root' })
export class PrefetchService {
  private readonly session = inject(AuthSessionService);
  private readonly users = inject(UserAdminService);
  private readonly profile = inject(ProfileService);
  private readonly settlements = inject(SettlementAuthoritiesService);
  private readonly branches = inject(BranchesService);
  private readonly coverages = inject(CoveragesRulesService);
  private readonly providers = inject(ProvidersService);
  private readonly fraud = inject(FraudRuleService);
  private readonly scoring = inject(ScoringRulesService);
  private readonly target = inject(ResolutionTargetService);
  private readonly cases = inject(CaseService);
  private readonly policies = inject(PolicyService);

  /**
   * Call from a home screen's constructor: runs once `loaded` turns true, so the home's own
   * requests go first. Keys already in the cache are not asked again.
   */
  afterHomeLoads(loaded: () => boolean): EffectRef {
    const watcher = effect(() => {
      if (loaded()) {
        untracked(() => this.run());
        watcher.destroy();
      }
    });
    return watcher;
  }

  private run(): void {
    const session = this.session.session();
    switch (session?.rol) {
      case 'REFERENTE_ASEGURADORA':
        this.users.prefetchList();
        this.settlements.prefetch();
        this.branches.prefetch();
        this.coverages.prefetch();
        this.providers.prefetch();
        this.fraud.prefetch();
        this.scoring.prefetch();
        this.target.prefetch();
        break;
      case 'ANALISTA_SINIESTROS':
        this.cases.prefetchList(INBOX_FIRST_PAGE);
        this.cases.prefetchLensSummary();
        this.cases.prefetchClaimCauseNames();
        this.users.prefetchAnalysts();
        break;
      case 'ASEGURADO':
        if (session.insuredId) {
          this.cases.prefetchList({ insuredId: session.insuredId, ...MY_CASES_FIRST_PAGE });
          this.policies.prefetch(session.insuredId);
        }
        this.profile.prefetch();
        break;
    }
  }
}
