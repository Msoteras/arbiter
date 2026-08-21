import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { BranchOption, BranchesService } from '../branches.service';
import { PeritoAdmin, PeritoRequest, PeritosService } from '../peritos.service';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { CheckboxComponent } from '../../../shared/ui/checkbox/checkbox.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';

/** Fila en edición. `id` null = alta. */
interface PeritoDraft extends PeritoRequest {
  id: number | null;
}

/**
 * Catálogo de peritos de la aseguradora. Igual que el scoring, es config de TODA la aseguradora y
 * no de un ramo, así que vive como sección propia de la pantalla de reglas y no dentro del
 * master-detail de ramos — aunque cada perito pueda especializarse en uno.
 *
 * Lo que se configura acá es a QUIÉN se deriva. Desde qué monto se puede derivar es una regla de
 * negocio y vive en el motor (rules-service), no acá.
 */
@Component({
  selector: 'app-peritos-config',
  imports: [
    ButtonComponent,
    CardComponent,
    CheckboxComponent,
    InputComponent,
    SelectComponent,
    EmptyStateComponent,
    BadgeComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './peritos-config.component.html',
  styleUrl: './peritos-config.component.scss',
})
export class PeritosConfigComponent {
  private readonly peritosService = inject(PeritosService);
  private readonly branchesService = inject(BranchesService);

  protected readonly peritos = signal<PeritoAdmin[]>([]);
  protected readonly branches = signal<BranchOption[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  /** Null = ninguna fila en edición. */
  protected readonly draft = signal<PeritoDraft | null>(null);
  protected readonly saving = signal(false);

  /** La opción vacía es "Todos los ramos", que es un valor real (generalista), no un "sin elegir". */
  protected readonly branchOptions = computed<SelectOption[]>(() => [
    { value: '', label: 'Todos los ramos' },
    ...this.branches().map((b) => ({ value: String(b.id), label: b.name })),
  ]);

  constructor() {
    this.reload();
    this.branchesService.list().subscribe({ next: (list) => this.branches.set(list) });
  }

  private reload(): void {
    this.loading.set(true);
    this.peritosService.list().subscribe({
      next: (list) => {
        this.peritos.set(list);
        this.loading.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.loading.set(false);
        this.error.set(err.error?.detail || 'No se pudo cargar el catálogo de peritos');
      },
    });
  }

  protected add(): void {
    this.error.set(null);
    this.draft.set({ id: null, name: '', email: '', zone: '', branchId: null, active: true });
  }

  protected edit(perito: PeritoAdmin): void {
    this.error.set(null);
    this.draft.set({
      id: perito.id,
      name: perito.name,
      email: perito.email,
      zone: perito.zone ?? '',
      branchId: perito.branchId,
      active: perito.active,
    });
  }

  protected cancel(): void {
    this.draft.set(null);
  }

  protected setField<K extends keyof PeritoDraft>(field: K, value: PeritoDraft[K]): void {
    const current = this.draft();
    if (current) {
      this.draft.set({ ...current, [field]: value });
    }
  }

  protected setBranch(value: string): void {
    this.setField('branchId', value ? Number(value) : null);
  }

  /** '' es el generalista (todos los ramos), que es un valor real y no un "sin elegir". */
  protected branchValue(draft: PeritoDraft): string {
    return draft.branchId == null ? '' : String(draft.branchId);
  }

  protected readonly canSave = computed(() => {
    const d = this.draft();
    // El mail es el único canal con el perito: sin él la derivación no llega a ningún lado.
    return !!d && d.name.trim().length > 0 && d.email.trim().length > 0;
  });

  protected save(): void {
    const d = this.draft();
    if (!d || !this.canSave()) {
      return;
    }
    const request: PeritoRequest = {
      name: d.name.trim(),
      email: d.email.trim(),
      zone: d.zone?.trim() || null,
      branchId: d.branchId,
      active: d.active,
    };
    this.saving.set(true);
    this.error.set(null);
    const call = d.id == null
      ? this.peritosService.create(request)
      : this.peritosService.update(d.id, request);
    call.subscribe({
      next: () => {
        this.saving.set(false);
        this.draft.set(null);
        this.reload();
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.error.set(err.error?.detail || 'No se pudo guardar el perito');
      },
    });
  }

  /**
   * El backend rechaza con 409 el borrado de un perito que ya recibió derivaciones. El mensaje que
   * llega explica que hay que desactivarlo, así que se muestra tal cual en vez de un genérico.
   */
  protected remove(perito: PeritoAdmin): void {
    this.error.set(null);
    this.peritosService.remove(perito.id).subscribe({
      next: () => this.reload(),
      error: (err: HttpErrorResponse) =>
        this.error.set(err.error?.detail || 'No se pudo borrar el perito'),
    });
  }

  protected ramoLabel(perito: PeritoAdmin): string {
    return perito.branchName ?? 'Todos los ramos';
  }
}
