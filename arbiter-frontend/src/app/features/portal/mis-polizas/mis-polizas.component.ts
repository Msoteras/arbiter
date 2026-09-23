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
  { status: 'loading' } | { status: 'ok'; policies: Policy[] } | { status: 'error' };

/** Read-only: policies are the insurer's data, so corrections go to the insurer, not Arbiter. */
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

  // Expired policies included: here the insured is browsing, not choosing one for a claim.
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

  // Expired policies stay visible but collapsed, so they don't push current coverage down.
  protected readonly current = computed(() => this.policies().filter((p) => !isExpired(p)));
  protected readonly expired = computed(() => this.policies().filter(isExpired));

  protected readonly showExpired = signal(false);

  protected toggleExpired(): void {
    this.showExpired.update((v) => !v);
  }
}
