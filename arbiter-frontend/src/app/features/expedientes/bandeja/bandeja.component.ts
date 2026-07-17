import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, map, of, startWith } from 'rxjs';

import { ExpedienteService } from '../expediente.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import { clasificacionLabel } from '../../../core/models/clasificacion';
import { estadoLabel } from '../../../core/models/estado';

interface FieldItem { label: string; value: string | null; mono?: boolean; full?: boolean; }

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; data: ExpedienteResponse[] }
  | { status: 'error' };

@Component({
  selector: 'app-bandeja',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './bandeja.component.html',
  styleUrl: './bandeja.component.scss',
})
export class BandejaComponent {
  private readonly service = inject(ExpedienteService);

  private readonly state = toSignal(
    // El backend pagina (default 20). Todavía no hay controles de paginación acá —eso es
    // parte de la historia de Frontend "Búsqueda y filtrado de expedientes"— así que pedimos
    // un size grande como parche temporal para no perder expedientes de la vista.
    this.service.list(undefined, undefined, 0, 100).pipe(
      map((page): LoadState => ({ status: 'ok', data: page.content })),
      startWith<LoadState>({ status: 'loading' }),
      catchError(() => of<LoadState>({ status: 'error' })),
    ),
    { initialValue: { status: 'loading' } as LoadState },
  );

  protected readonly loading = computed(() => this.state().status === 'loading');
  protected readonly hasError = computed(() => this.state().status === 'error');

  protected readonly cases = computed<ExpedienteResponse[]>(() => {
    const s = this.state();
    return s.status === 'ok' ? s.data : [];
  });

  protected readonly isEmpty = computed(() => this.state().status === 'ok' && this.cases().length === 0);

  protected estadoLabel(status: string): string {
    return estadoLabel(status);
  }

  /** Todos los campos que devuelve GET /api/v1/cases para un expediente, sin recortar. */
  protected fields(c: ExpedienteResponse): FieldItem[] {
    return [
      { label: 'Rama', value: c.branch },
      { label: 'Producto', value: c.product },
      { label: 'Causa', value: c.claimCause },
      { label: 'Bien asegurado', value: c.insuredItem },
      { label: 'Asegurado', value: c.insuredId, mono: true },
      { label: 'N° de póliza', value: c.policyNumber, mono: true },
      { label: 'Fecha del hecho', value: new Date(c.eventDate).toLocaleDateString('es-AR') },
      { label: 'Ubicación', value: c.eventLocation },
      { label: 'Importe reclamado', value: c.claimedAmount != null ? `$${c.claimedAmount.toLocaleString()}` : null },
      { label: 'Clasificación', value: c.analysisClassification ? clasificacionLabel(c.analysisClassification) : null },
      { label: 'Confianza del modelo', value: c.analysisConfidence != null ? `${Math.round(c.analysisConfidence * 100)}%` : null },
      { label: 'Detalle de la clasificación', value: c.analysisDetail, full: true },
      { label: 'Descripción', value: c.description, full: true },
    ];
  }
}
