import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { InsuredSessionService } from '../../../core/auth/insured-session.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import { estadoLabel, isEstadoFinal, proximoPaso } from '../../../core/models/estado';
import { ExpedienteService } from '../../expedientes/expediente.service';

type LoadState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ok'; data: ExpedienteResponse[] }
  | { status: 'error' };

/**
 * Portal del asegurado — sus expedientes, con estado actual y próximo paso.
 * Consume GET /api/v1/cases?insuredId=… (el filtro por asegurado del backend);
 * la identidad sale de InsuredSessionService (stub hasta integrar Auth0).
 */
@Component({
  selector: 'app-mis-expedientes',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './mis-expedientes.component.html',
  styleUrl: './mis-expedientes.component.scss',
})
export class MisExpedientesComponent {
  private readonly service = inject(ExpedienteService);
  protected readonly session = inject(InsuredSessionService);

  protected readonly identityInput = signal('');

  private readonly state = toSignal(
    toObservable(this.session.insuredId).pipe(
      switchMap((insuredId) => {
        if (!insuredId) {
          return of<LoadState>({ status: 'idle' });
        }
        // Tamaño grande como parche temporal hasta que haya paginación real en esta vista.
        return this.service.list(undefined, insuredId, 0, 100).pipe(
          map((page): LoadState => ({ status: 'ok', data: page.content })),
          startWith<LoadState>({ status: 'loading' }),
          catchError(() => of<LoadState>({ status: 'error' })),
        );
      }),
    ),
    { initialValue: { status: 'idle' } as LoadState },
  );

  protected readonly needsIdentity = computed(() => this.session.insuredId() === null);
  protected readonly loading = computed(() => this.state().status === 'loading');
  protected readonly hasError = computed(() => this.state().status === 'error');

  protected readonly cases = computed<ExpedienteResponse[]>(() => {
    const s = this.state();
    return s.status === 'ok' ? s.data : [];
  });

  protected readonly isEmpty = computed(
    () => this.state().status === 'ok' && this.cases().length === 0,
  );

  protected identify(): void {
    this.session.identify(this.identityInput());
  }

  protected onIdentityInput(e: Event): void {
    this.identityInput.set((e.target as HTMLInputElement).value);
  }

  protected estadoLabel(status: string): string {
    return estadoLabel(status);
  }

  protected proximoPaso(status: string): string {
    return proximoPaso(status);
  }

  protected isFinal(status: string): boolean {
    return isEstadoFinal(status);
  }

  protected fechaDenuncia(c: ExpedienteResponse): string {
    return c.createdAt ? new Date(c.createdAt).toLocaleDateString('es-AR') : '—';
  }
}
