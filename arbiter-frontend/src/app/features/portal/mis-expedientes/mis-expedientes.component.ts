import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { InsuredSessionService } from '../../../core/auth/insured-session.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import { estadoBadgeLabelAsegurado, estadoTone, isEstadoFinal, proximoPaso } from '../../../core/models/estado';
import { StatusTone } from '../../../core/models/status-tone';
import { ExpedienteService } from '../../expedientes/expediente.service';
import { NewClaimModalService } from '../../expedientes/new-claim-modal.service';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { listStagger, staggerReveal } from '../../../shared/animations';

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
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    CardComponent,
    BadgeComponent,
    ButtonComponent,
    InputComponent,
    InlineLoadingComponent,
  ],
  animations: [staggerReveal, listStagger],
  templateUrl: './mis-expedientes.component.html',
  styleUrl: './mis-expedientes.component.scss',
})
export class MisExpedientesComponent {
  private readonly service = inject(ExpedienteService);
  private readonly newClaim = inject(NewClaimModalService);
  protected readonly session = inject(InsuredSessionService);

  protected readonly identityInput = signal('');

  private readonly state = toSignal(
    toObservable(this.session.insuredId).pipe(
      switchMap((insuredId) => {
        if (!insuredId) {
          return of<LoadState>({ status: 'idle' });
        }
        // Tamaño grande como parche temporal hasta que haya paginación real en esta vista.
        return this.service.list({ insuredId, page: 0, size: 100 }).pipe(
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

  protected nuevaDenuncia(): void {
    this.newClaim.open();
  }

  protected estadoLabel(status: string): string {
    return estadoBadgeLabelAsegurado(status);
  }

  protected estadoTone(status: string): StatusTone {
    return estadoTone(status);
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
