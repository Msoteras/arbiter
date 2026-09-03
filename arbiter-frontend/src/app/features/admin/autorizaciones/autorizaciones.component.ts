import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import {
  PendingSettlement,
  SettlementAuthoritiesService,
} from '../settlement-authorities.service';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { ModalComponent } from '../../../shared/ui/modal/modal.component';
import { TextareaComponent } from '../../../shared/ui/textarea/textarea.component';

/**
 * Las liquidaciones que superaron la atribución del analista y esperan la firma del referente
 * (Anexo II del procedimiento de la compañía).
 *
 * Pantalla propia y no una solapa de Reglas: esto no se configura, se resuelve. Es trabajo diario
 * con expedientes concretos, del mismo lado que la bandeja, mientras que Reglas es el lugar donde
 * se decide cómo tiene que comportarse el sistema.
 *
 * Los expedientes de acá NO están en un estado especial: siguen en revisión del analista. Lo que
 * espera es la liquidación, no el siniestro — para el asegurado esto es interno y no lo ve.
 */
@Component({
  selector: 'app-autorizaciones',
  imports: [
    RouterLink,
    BadgeComponent,
    ButtonComponent,
    CardComponent,
    EmptyStateComponent,
    InlineLoadingComponent,
    ModalComponent,
    TextareaComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './autorizaciones.component.html',
  styleUrl: './autorizaciones.component.scss',
})
export class AutorizacionesComponent {
  private readonly service = inject(SettlementAuthoritiesService);

  protected readonly pending = signal<PendingSettlement[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly actingOn = signal<number | null>(null);

  /** Expediente cuya liquidación se está devolviendo. Null = el modal está cerrado. */
  protected readonly returning = signal<PendingSettlement | null>(null);
  protected readonly returnReason = signal('');

  constructor() {
    this.load();
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

  protected authorize(row: PendingSettlement): void {
    this.actingOn.set(row.caseId);
    this.error.set(null);
    this.service.authorize(row.caseId).subscribe({
      next: () => {
        this.actingOn.set(null);
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

  /**
   * Cuánto lleva esperando, en palabras. El siniestro consume su plazo legal de 30 días mientras
   * está acá (art. 56 LS), así que la espera no es un dato de color.
   */
  protected espera(days: number): string {
    if (days <= 0) {
      return 'Hoy';
    }
    return days === 1 ? 'Hace 1 día' : `Hace ${days} días`;
  }

  /** A partir de una semana esperando, la fila se marca: ya es demora, no cola normal. */
  protected demorado(days: number): boolean {
    return days >= 7;
  }
}
