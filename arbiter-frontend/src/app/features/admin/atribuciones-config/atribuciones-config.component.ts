import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { forkJoin } from 'rxjs';

import {
  SettlementAuthoritiesService,
  SettlementAuthority,
} from '../settlement-authorities.service';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { SaveBarComponent } from '../../../shared/ui/save-bar/save-bar.component';

/**
 * How much an analyst may authorize alone per branch; above it the settlement waits for the
 * referente. A branch without a cap means any amount is allowed, hence "Sin tope" rather than 0.
 */
@Component({
  selector: 'app-atribuciones-config',
  imports: [CardComponent, InputComponent, InlineLoadingComponent, SaveBarComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './atribuciones-config.component.html',
  styleUrl: './atribuciones-config.component.scss',
})
export class AtribucionesConfigComponent {
  private readonly service = inject(SettlementAuthoritiesService);
  private static readonly miles = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 0 });

  protected readonly authorities = signal<SettlementAuthority[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Digits typed per branch: empty means "no cap", which is not the same as 0. */
  protected readonly drafts = signal<Record<number, string>>({});

  /** Branches whose cap differs from the saved one; a single save sends them all. */
  private readonly changed = computed(() =>
    this.authorities().filter((a) => (this.drafts()[a.branchId] ?? '') !== stored(a)),
  );
  protected readonly dirty = computed(() => this.changed().length > 0);

  constructor() {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.service.list().subscribe({
      next: (list) => {
        this.authorities.set(list);
        this.resetDrafts(list);
        this.loading.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.error.set(e.error?.detail ?? 'No se pudieron cargar las atribuciones.');
        this.loading.set(false);
      },
    });
  }

  private resetDrafts(list: SettlementAuthority[]): void {
    this.drafts.set(Object.fromEntries(list.map((a) => [a.branchId, stored(a)])));
  }

  protected draftLabel(branchId: number): string {
    const digits = this.drafts()[branchId] ?? '';
    return digits === '' ? '' : AtribucionesConfigComponent.miles.format(Number(digits));
  }

  /** Only digits are kept: the thousands separators added by formatting aren't part of the value. */
  protected setDraft(branchId: number, value: string): void {
    const digits = value.replace(/\D/g, '').replace(/^0+(?=\d)/, '');
    this.drafts.update((d) => ({ ...d, [branchId]: digits }));
  }

  protected discard(): void {
    this.error.set(null);
    this.resetDrafts(this.authorities());
  }

  protected save(): void {
    const changed = this.changed();
    if (changed.length === 0) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    forkJoin(
      changed.map((a) => {
        const digits = this.drafts()[a.branchId] ?? '';
        return this.service.set(a.branchId, digits === '' ? null : Number(digits));
      }),
    ).subscribe({
      next: () => {
        this.saving.set(false);
        this.load();
      },
      error: (e: HttpErrorResponse) => {
        this.saving.set(false);
        // No reload: the typed values stay on screen so the user can retry.
        this.error.set(e.error?.detail ?? 'No se pudieron guardar los topes.');
      },
    });
  }
}

function stored(a: SettlementAuthority): string {
  return a.maxAmount == null ? '' : String(Math.round(a.maxAmount));
}
