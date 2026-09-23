import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';

import {
  PendingSettlement,
  AuthorizedSettlement,
  SettlementAuthoritiesService,
} from '../settlement-authorities.service';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { ChipGroupComponent, ChipOption } from '../../../shared/ui/chip-group/chip-group.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { ModalComponent } from '../../../shared/ui/modal/modal.component';
import { TableComponent } from '../../../shared/ui/table/table.component';
import { TextareaComponent } from '../../../shared/ui/textarea/textarea.component';
import { fadeStagger, staggerReveal } from '../../../shared/animations';
import { StatusTone } from '../../../core/models/status-tone';
import { formatDateTime } from '../../../core/util/datetime';

/**
 * Settlements over the analyst's authority, awaiting the referente's signature. These cases have no
 * special status (they stay in analyst review): only the settlement waits, invisibly to the insured.
 */
type Tab = 'pending' | 'authorized';

@Component({
  selector: 'app-autorizaciones',
  imports: [
    NgTemplateOutlet,
    RouterLink,
    BadgeComponent,
    ButtonComponent,
    CardComponent,
    ChipGroupComponent,
    EmptyStateComponent,
    InlineLoadingComponent,
    ModalComponent,
    TableComponent,
    TextareaComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [fadeStagger, staggerReveal],
  templateUrl: './autorizaciones.component.html',
  styleUrl: './autorizaciones.component.scss',
})
export class AutorizacionesComponent {
  private readonly service = inject(SettlementAuthoritiesService);

  protected readonly tab = signal<Tab>('pending');
  protected readonly pending = signal<PendingSettlement[]>([]);
  /** Fetched only when its tab opens, and refetched every time. */
  protected readonly authorized = signal<AuthorizedSettlement[]>([]);
  protected readonly authorizedLoading = signal(false);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly actingOn = signal<number | null>(null);

  /** Row about to be authorized; null = the confirmation modal is closed. */
  protected readonly authorizing = signal<PendingSettlement | null>(null);

  /** Row being returned; null = the modal is closed. */
  protected readonly returning = signal<PendingSettlement | null>(null);
  protected readonly returnReason = signal('');

  protected readonly tabOptions = computed<ChipOption[]>(() => [
    {
      value: 'pending',
      label: this.pending().length > 0 ? `Pendientes (${this.pending().length})` : 'Pendientes',
    },
    { value: 'authorized', label: 'Autorizadas' },
  ]);

  protected readonly resumenPendientes = computed(() => {
    const list = this.pending();
    const total = list.reduce((sum, row) => sum + row.settledAmount, 0);
    const cuantas = list.length === 1 ? '1 pendiente' : `${list.length} pendientes`;
    return `${cuantas} · ${this.monto(total)} a autorizar`;
  });

  constructor() {
    this.load();
  }

  protected setTab(value: string): void {
    const tab = value as Tab;
    this.tab.set(tab);
    if (tab === 'authorized') {
      this.loadAuthorized();
    }
  }

  private loadAuthorized(): void {
    this.authorizedLoading.set(true);
    this.authorized.set([]);
    this.error.set(null);
    this.service.authorized().subscribe({
      next: (list) => {
        this.authorized.set(list);
        this.authorizedLoading.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.error.set(e.error?.detail ?? 'No se pudieron cargar las liquidaciones autorizadas.');
        this.authorizedLoading.set(false);
      },
    });
  }

  private load(): void {
    this.loading.set(true);
    this.service.pending().subscribe({
      next: (list) => {
        this.pending.set(list);
        this.loading.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.error.set(e.error?.detail ?? 'No se pudieron cargar las liquidaciones pendientes.');
        this.loading.set(false);
      },
    });
  }

  // Authorizing approves the claim and notifies the insured, so it asks first, like returning.
  protected askAuthorize(row: PendingSettlement): void {
    this.authorizing.set(row);
    this.error.set(null);
  }

  protected cancelAuthorize(): void {
    this.authorizing.set(null);
  }

  protected confirmAuthorize(): void {
    const row = this.authorizing();
    if (!row) {
      return;
    }
    this.actingOn.set(row.caseId);
    this.error.set(null);
    this.service.authorize(row.caseId).subscribe({
      next: () => {
        this.actingOn.set(null);
        this.authorizing.set(null);
        this.load();
      },
      error: (e: HttpErrorResponse) => {
        this.actingOn.set(null);
        this.error.set(e.error?.detail ?? 'No se pudo autorizar la liquidación.');
      },
    });
  }

  protected askReturn(row: PendingSettlement): void {
    this.returning.set(row);
    this.returnReason.set('');
    this.error.set(null);
  }

  protected cancelReturn(): void {
    this.returning.set(null);
  }

  protected confirmReturn(): void {
    const row = this.returning();
    const reason = this.returnReason().trim();
    if (!row || !reason) {
      return;
    }
    this.actingOn.set(row.caseId);
    this.service.returnToAnalyst(row.caseId, reason).subscribe({
      next: () => {
        this.actingOn.set(null);
        this.returning.set(null);
        this.load();
      },
      error: (e: HttpErrorResponse) => {
        this.actingOn.set(null);
        this.error.set(e.error?.detail ?? 'No se pudo devolver la liquidación.');
      },
    });
  }

  protected monto(amount: number | null): string {
    if (amount == null) {
      return '—';
    }
    return new Intl.NumberFormat('es-AR', {
      style: 'currency',
      currency: 'ARS',
      maximumFractionDigits: 0,
    }).format(amount);
  }

  protected espera(days: number): string {
    if (days <= 0) {
      return 'Hoy';
    }
    return days === 1 ? 'Hace 1 día' : `Hace ${days} días`;
  }

  /**
   * The legal 30-day deadline (art. 56) keeps running while waiting here: after a week it's a delay,
   * after three there's little margin left to pay on time.
   */
  protected esperaTone(days: number): StatusTone {
    if (days >= 21) return 'danger';
    if (days >= 7) return 'warning';
    return 'neutral';
  }

  /** Share of the bar covered by the cap; the rest is the excess. */
  protected topePct(row: { settledAmount: number; authorityLimit: number | null }): number {
    if (row.authorityLimit == null || row.settledAmount <= 0) {
      return 100;
    }
    return Math.min(100, (row.authorityLimit / row.settledAmount) * 100);
  }

  protected formatDateTime = formatDateTime;
}
