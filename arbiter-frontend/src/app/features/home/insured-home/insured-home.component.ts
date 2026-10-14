import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { PrefetchService } from '../prefetch.service';
import { InsuredSessionService } from '../../../core/auth/insured-session.service';
import { CaseResponse } from '../../../core/models/case';
import {
  InsuredStatusCode,
  insuredStatusDescription,
  insuredStatusTitle,
  isFinalInsuredStatus,
} from '../../../core/models/insured-status';
import { StatusTone } from '../../../core/models/status-tone';
import { longDate, greetingForTimeOfDay } from '../../../core/util/datetime';
import { CaseService } from '../../cases/case.service';
import { NewClaimModalService } from '../../cases/new-claim-modal.service';
import { CaseMessagesService } from '../../cases/case-messages.service';
import {
  InsuredChatComponent,
  analystFirstName,
  initialsOf,
} from '../../portal/insured-chat/insured-chat.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { LoadingComponent } from '../../../shared/ui/loading/loading.component';
import { staggerReveal } from '../../../shared/animations';

type LoadState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ok'; data: CaseResponse[] }
  | { status: 'error' };

type StepState = 'done' | 'active' | 'pending';

interface Step {
  label: string;
  n: number;
  state: StepState;
  /** Only on the resolution step. */
  tone?: StatusTone;
}

/** Insured-facing copy: never mention the model's classification or the internal scoring. */
@Component({
  selector: 'app-insured-home',
  imports: [
    RouterLink,
    CardComponent,
    BadgeComponent,
    ButtonComponent,
    EmptyStateComponent,
    LoadingComponent,
    InsuredChatComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [staggerReveal],
  templateUrl: './insured-home.component.html',
  styleUrl: './insured-home.component.scss',
})
export class InsuredHomeComponent {
  private readonly service = inject(CaseService);
  private readonly messages = inject(CaseMessagesService);
  private readonly session = inject(AuthSessionService);
  protected readonly insured = inject(InsuredSessionService);
  protected readonly newClaim = inject(NewClaimModalService);

  protected readonly greeting = greetingForTimeOfDay();
  protected readonly today = longDate();
  protected readonly firstName = computed(() => this.session.session()?.nombre ?? '');

  protected readonly needsIdentity = computed(() => this.insured.insuredId() === null);

  private readonly state = toSignal(
    toObservable(this.insured.insuredId).pipe(
      switchMap((insuredId) => {
        if (!insuredId) {
          return of<LoadState>({ status: 'idle' });
        }
        return this.service.cachedList({ insuredId, page: 0, size: 100 }).pipe(
          map((page): LoadState => ({ status: 'ok', data: page.content })),
          startWith<LoadState>({ status: 'loading' }),
          catchError(() => of<LoadState>({ status: 'error' })),
        );
      }),
    ),
    { initialValue: { status: 'idle' } as LoadState },
  );

  protected readonly loading = computed(() => this.state().status === 'loading');

  /** Once the home has loaded, warms the screens this role opens most (`PrefetchService`). */
  private readonly warmUp = inject(PrefetchService).afterHomeLoads(() => !this.loading());
  protected readonly hasError = computed(() => this.state().status === 'error');

  protected readonly cases = computed<CaseResponse[]>(() => {
    const s = this.state();
    return s.status === 'ok' ? s.data : [];
  });

  protected readonly isEmpty = computed(
    () => this.state().status === 'ok' && this.cases().length === 0,
  );

  // The list comes newest first (default sort id,desc).
  protected readonly featured = computed<CaseResponse | null>(() => this.cases()[0] ?? null);

  protected readonly inProgress = computed(
    () => this.cases().filter((c) => !isFinalInsuredStatus(c.insuredStatus.code)).length,
  );
  protected readonly closed = computed(
    () => this.cases().filter((c) => isFinalInsuredStatus(c.insuredStatus.code)).length,
  );

  // "En trámite" rather than "En análisis": the phase also covers verification and repair.
  protected readonly steps = computed<Step[]>(() => {
    const d = this.featured();
    if (!d) {
      return [];
    }
    const code = d.insuredStatus.code;
    const resolved = isFinalInsuredStatus(code);
    return [
      // Always behind: the insured is never told the case is still being classified.
      { label: 'Denuncia recibida', n: 1, state: 'done' },
      { label: 'En trámite', n: 2, state: resolved ? 'done' : 'active' },
      {
        label: 'Resolución',
        n: 3,
        // Green only for APPROVED: rejection and lapse are terminal too, but not good news.
        state: resolved ? 'done' : 'pending',
        tone: resolved ? (code === 'APPROVED' ? 'ok' : 'danger') : undefined,
      },
    ];
  });

  protected readonly chatOpen = signal(false);

  /** Only while unread: once opened, the notice has done its job. */
  protected readonly newMessage = computed(() => {
    const d = this.featured();
    const item = d ? this.messages.inboxItem(d.id, d.insurerSlug) : null;
    return item && item.unreadCount > 0 && item.lastMessageSender === 'ANALYST' ? item : null;
  });
  protected readonly nmInitials = computed(() => initialsOf(this.newMessage()?.analystName));
  protected readonly nmTitle = computed(() => {
    const analyst = analystFirstName(this.newMessage());
    return analyst
      ? `Nuevo mensaje de ${analyst}, tu analista`
      : 'Nuevo mensaje del equipo de siniestros';
  });

  protected connectorDone(i: number): boolean {
    const d = this.featured();
    if (!d) {
      return false;
    }
    return i === 0 || isFinalInsuredStatus(d.insuredStatus.code);
  }

  protected statusTitle(code: InsuredStatusCode): string {
    return insuredStatusTitle(code);
  }

  protected description(code: InsuredStatusCode): string {
    return insuredStatusDescription(code);
  }

  protected progressLabel(code: InsuredStatusCode): string {
    return isFinalInsuredStatus(code) ? 'Terminado' : 'En trámite';
  }

  protected statusTone(code: InsuredStatusCode): StatusTone {
    if (isFinalInsuredStatus(code)) {
      return code === 'APPROVED' ? 'ok' : 'danger';
    }
    return 'info';
  }

  protected reportedOn(c: CaseResponse): string {
    return c.createdAt ? new Date(c.createdAt).toLocaleDateString('es-AR') : '—';
  }
}
