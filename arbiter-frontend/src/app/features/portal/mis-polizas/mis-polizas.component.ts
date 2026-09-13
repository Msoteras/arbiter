import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith } from 'rxjs';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { Policy, isExpired } from '../../../core/models/policy';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { PolicyCardComponent } from '../../../shared/ui/policy-card/policy-card.component';
import { PolicyService } from '../../expedientes/policy.service';

type PoliciesState =
  | { status: 'loading' }
  | { status: 'ok'; policies: Policy[] }
  | { status: 'error' };

/**
 * "Mis pólizas" del asegurado, en solo lectura.
 *
 * Hasta acá las pólizas se le mostraban una sola vez, en el onboarding, y después el único lugar
 * donde volvían a aparecer era el selector del alta de denuncia: para ver qué tenía cubierto había
 * que arrancar una denuncia. Esta pantalla responde la pregunta que motiva casi todas las
 * consultas —cuánto me cubre y cuánta franquicia tengo— sin empezar un trámite.
 *
 * Nada se edita: la póliza es dato de la compañía (decisión #10). Si algo no coincide, el reclamo
 * va a la aseguradora, no a Arbiter.
 */
@Component({
  selector: 'app-mis-polizas',
  imports: [EmptyStateComponent, InlineLoadingComponent, PolicyCardComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './mis-polizas.component.html',
  styleUrl: './mis-polizas.component.scss',
})
export class MisPolizasComponent {
  private readonly policyService = inject(PolicyService);
  private readonly session = inject(AuthSessionService);

  // Con las vencidas incluidas: el asegurado viene a consultar, no a elegir. El alta de denuncia
  // sigue pidiendo solo las vigentes, que ahí ofrecer una vencida termina en un rechazo.
  private readonly state = toSignal(
    this.policyService.listByInsured(this.session.session()?.insuredId ?? '', true).pipe(
      map((policies): PoliciesState => ({ status: 'ok', policies })),
      startWith<PoliciesState>({ status: 'loading' }),
      catchError(() => of<PoliciesState>({ status: 'error' })),
    ),
    { initialValue: { status: 'loading' } as PoliciesState },
  );

  protected readonly loading = computed(() => this.state().status === 'loading');
  protected readonly loadError = computed(() => this.state().status === 'error');

  private readonly policies = computed<Policy[]>(() => {
    const state = this.state();
    return state.status === 'ok' ? state.policies : [];
  });

  protected readonly empty = computed(
    () => this.state().status === 'ok' && this.policies().length === 0,
  );

  /**
   * Las vencidas van aparte y plegadas. Siguen estando —esconderlas deja al asegurado sin saber
   * por qué desapareció la del año pasado— pero no pueden empujar hacia abajo lo que sí lo cubre
   * hoy: con una póliza por celular, la lista de un asegurado viejo es mayormente historia.
   */
  protected readonly current = computed(() => this.policies().filter((p) => !isExpired(p)));
  protected readonly expired = computed(() => this.policies().filter(isExpired));

  protected readonly showExpired = signal(false);

  protected toggleExpired(): void {
    this.showExpired.update((v) => !v);
  }
}
