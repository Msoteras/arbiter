import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import {
  SettlementAuthoritiesService,
  SettlementAuthority,
} from '../settlement-authorities.service';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';

/**
 * Hasta cuánto autoriza un analista por su cuenta en cada ramo — las atribuciones del Anexo II del
 * procedimiento de la compañía. Por encima de ese monto la liquidación espera la firma del
 * referente.
 *
 * Va acá y no en el detalle de cada ramo porque es una tabla que se lee de una: el referente
 * compara los topes entre sí ("celulares hasta tanto, tecnología hasta tanto"), y repartirlos en
 * cinco solapas obliga a entrar y salir para tener el panorama.
 *
 * Un ramo sin tope no está a medio configurar: significa que el analista autoriza cualquier monto,
 * que es como funcionaba antes de que esto existiera. Por eso la fila vacía dice "Sin tope" y no
 * un cero ni un guion.
 */
@Component({
  selector: 'app-atribuciones-config',
  imports: [ButtonComponent, CardComponent, InputComponent, InlineLoadingComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './atribuciones-config.component.html',
  styleUrl: './atribuciones-config.component.scss',
})
export class AtribucionesConfigComponent {
  private readonly service = inject(SettlementAuthoritiesService);

  protected readonly authorities = signal<SettlementAuthority[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  /** Lo tipeado por ramo, como texto: vacío es "sin tope", que no es lo mismo que 0. */
  protected readonly drafts = signal<Record<number, string>>({});
  protected readonly savingBranchId = signal<number | null>(null);

  constructor() {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.service.list().subscribe({
      next: (list) => {
        this.authorities.set(list);
        this.drafts.set(
          Object.fromEntries(list.map((a) => [a.branchId, a.maxAmount == null ? '' : String(a.maxAmount)])),
        );
        this.loading.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.error.set(e.error?.detail ?? 'No se pudieron cargar las atribuciones.');
        this.loading.set(false);
      },
    });
  }

  protected draftFor(branchId: number): string {
    return this.drafts()[branchId] ?? '';
  }

  protected setDraft(branchId: number, value: string): void {
    this.drafts.update((d) => ({ ...d, [branchId]: value }));
  }

  /** Cambió respecto de lo guardado: sin esto el botón ofrece guardar lo mismo que ya está. */
  protected isDirty(a: SettlementAuthority): boolean {
    const stored = a.maxAmount == null ? '' : String(a.maxAmount);
    return this.draftFor(a.branchId).trim() !== stored;
  }

  protected save(a: SettlementAuthority): void {
    const raw = this.draftFor(a.branchId).trim();
    const amount = raw === '' ? null : Number(raw);
    if (amount != null && (!Number.isFinite(amount) || amount < 0)) {
      this.error.set('El tope tiene que ser un número mayor o igual a cero.');
      return;
    }

    this.savingBranchId.set(a.branchId);
    this.error.set(null);
    this.service.set(a.branchId, amount).subscribe({
      next: () => {
        this.savingBranchId.set(null);
        this.load();
      },
      error: (e: HttpErrorResponse) => {
        this.savingBranchId.set(null);
        this.error.set(e.error?.detail ?? 'No se pudo guardar el tope.');
      },
    });
  }

  protected montoLabel(amount: number | null): string {
    if (amount == null) {
      return 'Sin tope';
    }
    return new Intl.NumberFormat('es-AR', {
      style: 'currency',
      currency: 'ARS',
      maximumFractionDigits: 0,
    }).format(amount);
  }
}
