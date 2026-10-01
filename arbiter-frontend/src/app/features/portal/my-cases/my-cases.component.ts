import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import {
  catchError,
  debounceTime,
  distinctUntilChanged,
  map,
  of,
  startWith,
  switchMap,
} from 'rxjs';

import { InsuredSessionService } from '../../../core/auth/insured-session.service';
import { CaseResponse } from '../../../core/models/case';
import {
  SIMPLIFIED_STATUSES,
  SimplifiedStatus,
  insuredStatusBadgeLabel,
  caseStatusTone,
  statusesInBucket,
  isFinalStatus,
  nextStepLabel,
} from '../../../core/models/case-status';
import { Policy } from '../../../core/models/policy';
import { StatusTone } from '../../../core/models/status-tone';
import { ExpedienteService } from '../../expedientes/expediente.service';
import { NewClaimModalService } from '../../expedientes/new-claim-modal.service';
import { PolicyService } from '../../expedientes/policy.service';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { PaginationComponent } from '../../../shared/ui/pagination/pagination.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { listStagger, staggerReveal } from '../../../shared/animations';

type LoadState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ok'; data: CaseResponse[]; totalElements: number; totalPages: number }
  | { status: 'error' };

@Component({
  selector: 'app-my-cases',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    CardComponent,
    BadgeComponent,
    ButtonComponent,
    InputComponent,
    InlineLoadingComponent,
    PaginationComponent,
    SelectComponent,
  ],
  animations: [staggerReveal, listStagger],
  templateUrl: './my-cases.component.html',
  styleUrl: './my-cases.component.scss',
})
export class MyCasesComponent {
  private readonly service = inject(ExpedienteService);
  private readonly policyService = inject(PolicyService);
  private readonly newClaim = inject(NewClaimModalService);
  protected readonly session = inject(InsuredSessionService);

  protected readonly identityInput = signal('');

  protected readonly qDraft = signal('');
  protected readonly statusFilter = signal<SimplifiedStatus | ''>('');
  protected readonly dateFrom = signal('');
  protected readonly dateTo = signal('');
  protected readonly insurerFilter = signal('');
  protected readonly page = signal(0);
  protected readonly size = signal(10);

  protected readonly statusOptions: SelectOption[] = SIMPLIFIED_STATUSES;

  private readonly qDebounced = toSignal(
    toObservable(this.qDraft).pipe(debounceTime(350), distinctUntilChanged()),
    { initialValue: '' },
  );

  // Insurers come from policies, not loaded cases: a page may not include every insurer.
  private readonly policies = toSignal(
    toObservable(this.session.insuredId).pipe(
      switchMap((insuredId) => (insuredId ? this.policies$(insuredId) : of<Policy[]>([]))),
    ),
    { initialValue: [] as Policy[] },
  );

  protected readonly insurerOptions = computed<SelectOption[]>(() => {
    const byId = new Map(this.policies().map((p) => [p.insurerId, p.insurerName]));
    return [...byId].map(([value, label]) => ({ value, label }));
  });

  protected readonly showInsurerFilter = computed(() => this.insurerOptions().length > 1);

  private readonly params = computed(() => ({
    insuredId: this.session.insuredId(),
    q: this.qDebounced() || undefined,
    status: this.statusFilter()
      ? statusesInBucket(this.statusFilter() as SimplifiedStatus)
      : undefined,
    eventDateFrom: this.dateFrom() || undefined,
    eventDateTo: this.dateTo() || undefined,
    insurerId: this.insurerFilter() ? Number(this.insurerFilter()) : undefined,
    page: this.page(),
    size: this.size(),
  }));

  private readonly state = toSignal(
    toObservable(this.params).pipe(
      switchMap(({ insuredId, ...filters }) => {
        if (!insuredId) {
          return of<LoadState>({ status: 'idle' });
        }
        return this.service.list({ insuredId, ...filters }).pipe(
          map((page): LoadState => ({
            status: 'ok',
            data: page.content,
            totalElements: page.totalElements,
            totalPages: page.totalPages,
          })),
          startWith<LoadState>({ status: 'loading' }),
          catchError(() => of<LoadState>({ status: 'error' })),
        );
      }),
    ),
    { initialValue: { status: 'idle' } as LoadState },
  );

  // Includes expired policies: their past claims are still on the list.
  private policies$(insuredId: string) {
    return this.policyService
      .listByInsured(insuredId, true)
      .pipe(catchError(() => of<Policy[]>([])));
  }

  protected readonly needsIdentity = computed(() => this.session.insuredId() === null);
  protected readonly loading = computed(() => this.state().status === 'loading');
  protected readonly hasError = computed(() => this.state().status === 'error');

  protected readonly cases = computed<CaseResponse[]>(() => {
    const s = this.state();
    return s.status === 'ok' ? s.data : [];
  });

  protected readonly isEmpty = computed(
    () => this.state().status === 'ok' && this.cases().length === 0,
  );

  protected readonly totalElements = computed(() => {
    const s = this.state();
    return s.status === 'ok' ? s.totalElements : 0;
  });

  protected readonly totalPages = computed(() => {
    const s = this.state();
    return s.status === 'ok' ? s.totalPages : 0;
  });

  protected readonly hasFilters = computed(
    () =>
      !!(
        this.qDebounced() ||
        this.statusFilter() ||
        this.dateFrom() ||
        this.dateTo() ||
        this.insurerFilter()
      ),
  );

  protected onFilterChange(): void {
    this.page.set(0);
  }

  protected clearFilters(): void {
    this.qDraft.set('');
    this.statusFilter.set('');
    this.dateFrom.set('');
    this.dateTo.set('');
    this.insurerFilter.set('');
    this.page.set(0);
  }

  protected onPageChange(page: number): void {
    this.page.set(page);
  }

  protected onSizeChange(size: number): void {
    this.size.set(size);
    this.page.set(0);
  }

  protected identify(): void {
    this.session.identify(this.identityInput());
  }

  protected openNewClaim(): void {
    this.newClaim.open();
  }

  protected statusLabel(status: string): string {
    return insuredStatusBadgeLabel(status);
  }

  protected statusTone(status: string): StatusTone {
    return caseStatusTone(status);
  }

  protected nextStep(status: string): string {
    return nextStepLabel(status);
  }

  protected isFinal(status: string): boolean {
    return isFinalStatus(status);
  }

  protected reportedOn(c: CaseResponse): string {
    return c.createdAt ? new Date(c.createdAt).toLocaleDateString('es-AR') : '—';
  }

  protected occurredOn(c: CaseResponse): string {
    return c.eventDate ? new Date(c.eventDate).toLocaleDateString('es-AR') : '—';
  }
}
