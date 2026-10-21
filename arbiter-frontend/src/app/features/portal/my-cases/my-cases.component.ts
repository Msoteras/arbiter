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
  InsuredStatusCode,
  insuredNextStep,
  insuredStatusTone,
  isFinalInsuredStatus,
} from '../../../core/models/insured-status';
import { Policy } from '../../../core/models/policy';
import { StatusTone } from '../../../core/models/status-tone';
import { CaseService } from '../../cases/case.service';
import { NewClaimModalService } from '../../cases/new-claim-modal.service';
import { PolicyService } from '../../cases/policy.service';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import {
  DateRangePickerComponent,
  DateRangePreset,
} from '../../../shared/ui/date-picker/date-range-picker.component';
import { addDays, todayIso } from '../../../core/util/datetime';
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
    DateRangePickerComponent,
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
  private readonly service = inject(CaseService);
  private readonly policyService = inject(PolicyService);
  private readonly newClaim = inject(NewClaimModalService);
  protected readonly session = inject(InsuredSessionService);

  protected readonly identityInput = signal('');

  protected readonly qDraft = signal('');
  protected readonly statusFilter = signal<InsuredStatusCode | ''>('');
  protected readonly dateFrom = signal('');
  protected readonly dateTo = signal('');
  protected readonly today = todayIso();
  /** Open on the "Hasta" side: nothing can have happened after today. */
  protected readonly datePresets: readonly DateRangePreset[] = [
    { label: 'Últimos 30 días', from: addDays(this.today, -30), to: '' },
    { label: 'Últimos 90 días', from: addDays(this.today, -90), to: '' },
    { label: 'Este año', from: `${this.today.slice(0, 4)}-01-01`, to: '' },
  ];
  protected readonly insurerFilter = signal('');
  protected readonly page = signal(0);
  protected readonly size = signal(10);

  // The same statuses the cards show, with the backend's labels.
  protected readonly statusOptions = toSignal(
    this.service.insuredStatuses().pipe(
      map((statuses) => statuses.map((s): SelectOption => ({ value: s.code, label: s.label }))),
      catchError(() => of<SelectOption[]>([])),
    ),
    { initialValue: [] as SelectOption[] },
  );

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
    insuredStatus: this.statusFilter() ? [this.statusFilter() as InsuredStatusCode] : undefined,
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
        return this.service.cachedList({ insuredId, ...filters }).pipe(
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

  protected statusTone(code: InsuredStatusCode): StatusTone {
    return insuredStatusTone(code);
  }

  protected nextStep(code: InsuredStatusCode): string {
    return insuredNextStep(code);
  }

  protected isFinal(code: InsuredStatusCode): boolean {
    return isFinalInsuredStatus(code);
  }

  protected reportedOn(c: CaseResponse): string {
    return c.createdAt ? new Date(c.createdAt).toLocaleDateString('es-AR') : '—';
  }

  protected occurredOn(c: CaseResponse): string {
    return c.eventDate ? new Date(c.eventDate).toLocaleDateString('es-AR') : '—';
  }
}
