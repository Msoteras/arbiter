import { ChangeDetectionStrategy, Component, computed, effect, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, map, of, startWith } from 'rxjs';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { CaseService } from '../../cases/case.service';
import { CaseResponse } from '../../../core/models/case';
import { FINAL_STATUSES, caseStatusLabel, caseStatusTone } from '../../../core/models/case-status';
import { StatusTone } from '../../../core/models/status-tone';
import { longDate, greetingForTimeOfDay } from '../../../core/util/datetime';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { StatTileComponent } from '../../../shared/ui/stat-tile/stat-tile.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { LoadingComponent } from '../../../shared/ui/loading/loading.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { AppReadyService } from '../../../core/app-ready.service';
import { growBar, listStagger, staggerReveal } from '../../../shared/animations';

interface Counts {
  pending: number;
  inProgress: number;
  resolved: number;
  atRisk: number;
}

interface DistSegment {
  label: string;
  value: number;
  tone: 'info' | 'warning' | 'ok';
}

type CountsState = { status: 'loading' } | { status: 'ok'; counts: Counts } | { status: 'error' };

type ActionState =
  { status: 'loading' } | { status: 'ok'; data: CaseResponse[] } | { status: 'error' };

@Component({
  selector: 'app-analyst-home',
  imports: [
    RouterLink,
    CardComponent,
    StatTileComponent,
    BadgeComponent,
    ButtonComponent,
    EmptyStateComponent,
    LoadingComponent,
    InlineLoadingComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [staggerReveal, listStagger, growBar],
  templateUrl: './analyst-home.component.html',
  styleUrl: './analyst-home.component.scss',
})
export class AnalystHomeComponent {
  private readonly service = inject(CaseService);
  private readonly session = inject(AuthSessionService);
  private readonly appReady = inject(AppReadyService);

  protected readonly greeting = greetingForTimeOfDay();
  protected readonly today = longDate();
  protected readonly firstName = computed(() => this.session.session()?.nombre ?? '');

  private readonly countsState = toSignal(
    this.service.assignedSummary().pipe(
      map((s): CountsState => {
        // Awaiting the referente's sign-off: still under review, but the analyst already decided.
        const pending = Math.max(
          0,
          (s.byStatus['PENDING_ANALYST_REVIEW'] ?? 0) - s.awaitingReferent,
        );
        const resolved = FINAL_STATUSES.reduce((acc, e) => acc + (s.byStatus[e] ?? 0), 0);
        return {
          status: 'ok',
          counts: {
            pending,
            resolved,
            atRisk: s.highRisk,
            inProgress: Math.max(0, s.total - resolved),
          },
        };
      }),
      startWith<CountsState>({ status: 'loading' }),
      catchError(() => of<CountsState>({ status: 'error' })),
    ),
    { initialValue: { status: 'loading' } as CountsState },
  );

  protected readonly countsLoading = computed(() => this.countsState().status === 'loading');
  protected readonly counts = computed<Counts | null>(() => {
    const s = this.countsState();
    return s.status === 'ok' ? s.counts : null;
  });

  // Sort by the entity property `reportedAt`: the DTO's `eventDate` is rejected by Spring Data.
  private readonly actionState = toSignal(
    this.service
      .list({
        assignedToMe: true,
        status: 'PENDING_ANALYST_REVIEW',
        sort: 'reportedAt,asc',
        // Some may be awaiting the referente and get dropped below, hence more than 5.
        size: 15,
      })
      .pipe(
        map((page): ActionState => ({
          status: 'ok',
          data: page.content
            .filter((c) => c.settlementStatus !== 'PENDING_AUTHORIZATION')
            .slice(0, 5),
        })),
        startWith<ActionState>({ status: 'loading' }),
        catchError(() => of<ActionState>({ status: 'error' })),
      ),
    { initialValue: { status: 'loading' } as ActionState },
  );

  protected readonly actionLoading = computed(() => this.actionState().status === 'loading');
  protected readonly actionError = computed(() => this.actionState().status === 'error');
  protected readonly actionItems = computed<CaseResponse[]>(() => {
    const s = this.actionState();
    return s.status === 'ok' ? s.data : [];
  });
  protected readonly actionEmpty = computed(
    () => this.actionState().status === 'ok' && this.actionItems().length === 0,
  );

  protected readonly pageLoading = computed(() => this.countsLoading() || this.actionLoading());

  // The full-viewport loader shows only on app startup; later visits use an inline spinner.
  protected readonly showFullLoader = computed(() => this.pageLoading() && !this.appReady.ready());
  private readonly markReady = effect(() => {
    if (!this.pageLoading()) {
      this.appReady.markReady();
    }
  });

  protected readonly distSegments = computed<DistSegment[]>(() => {
    const c = this.counts();
    if (!c) return [];
    // Segments must be disjoint: `inProgress` already includes `pending`, and high-risk
    // overlaps every category, so it's shown apart.
    return [
      { label: 'Pendientes', value: c.pending, tone: 'info' },
      {
        label: 'Otros en trámite',
        value: Math.max(0, c.inProgress - c.pending),
        tone: 'warning',
      },
      { label: 'Resueltos', value: c.resolved, tone: 'ok' },
    ];
  });

  protected readonly distTotal = computed(() =>
    this.distSegments().reduce((sum, segment) => sum + segment.value, 0),
  );
  protected readonly hasCaseload = computed(() => this.distTotal() > 0);
  protected readonly atRisk = computed(() => this.counts()?.atRisk ?? 0);

  protected distPct(value: number): number {
    const total = this.distTotal();
    return total > 0 ? Math.round((value / total) * 100) : 0;
  }

  protected caseStatusLabel(status: string): string {
    return caseStatusLabel(status);
  }

  protected caseStatusTone(status: string): StatusTone {
    return caseStatusTone(status);
  }

  protected displayInsured(c: CaseResponse): string {
    return c.insuredName ?? 'Sin identificar';
  }
}
