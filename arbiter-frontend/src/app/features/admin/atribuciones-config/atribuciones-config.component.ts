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

  /** Lo tipeado por ramo, solo dígitos: vacío es "sin tope", que no es lo mismo que 0. */
  protected readonly drafts = signal<Record<number, string>>({});

  /** Los ramos cuyo tope cambió respecto de lo guardado. Un solo "Guardar cambios" los manda todos. */
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

  /** El monto con puntos de miles, como se lee una cifra en pesos. */
  protected draftLabel(branchId: number): string {
    const digits = this.drafts()[branchId] ?? '';
    return digits === '' ? '' : AtribucionesConfigComponent.miles.format(Number(digits));
  }

  /** Se guarda solo el número: los puntos que agrega el formato no son parte del valor. */
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
        // Sin releer: lo tipeado queda en pantalla para reintentar, en vez de perderse.
        this.error.set(e.error?.detail ?? 'No se pudieron guardar los topes.');
      },
    });
  }
}

function stored(a: SettlementAuthority): string {
  return a.maxAmount == null ? '' : String(Math.round(a.maxAmount));
}
