import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { InsuredSessionService } from '../../../core/auth/insured-session.service';
import { CaseResponse } from '../../../core/models/case';
import {
  SimplifiedStatus,
  insuredStatusBadgeLabel,
  insuredStatusDescription,
  simplifiedStatus,
  simplifiedStatusLabel,
  insuredStatusTitle,
  isFinalStatus,
} from '../../../core/models/case-status';
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

const ORDER: SimplifiedStatus[] = ['REPORTED', 'IN_PROGRESS', 'FINISHED'];

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
        return this.service.list({ insuredId, page: 0, size: 100 }).pipe(
          map((page): LoadState => ({ status: 'ok', data: page.content })),
          startWith<LoadState>({ status: 'loading' }),
          catchError(() => of<LoadState>({ status: 'error' })),
        );
      }),
    ),
    { initialValue: { status: 'idle' } as LoadState },
  );

  protected readonly loading = computed(() => this.state().status === 'loading');
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
    () => this.cases().filter((c) => !isFinalStatus(c.status)).length,
  );
  protected readonly closed = computed(
    () => this.cases().filter((c) => isFinalStatus(c.status)).length,
  );

  // "En trámite" rather than "En análisis": the phase also covers verification and repair.
  protected readonly steps = computed<Step[]>(() => {
    const d = this.featured();
    if (!d) {
      return [];
    }
    const idx = ORDER.indexOf(simplifiedStatus(d.status));
    const stateFor = (threshold: number): StepState =>
      idx > threshold ? 'done' : idx === threshold ? 'active' : 'pending';

    const resolved = idx >= 2;
    return [
      { label: 'Denuncia recibida', n: 1, state: stateFor(0) },
      { label: 'En trámite', n: 2, state: stateFor(1) },
      {
        label: 'Resolución',
        n: 3,
        // Green only for APPROVED: rejection and lapse are terminal too, but not good news.
        state: resolved ? 'done' : 'pending',
        tone: resolved ? (d.status === 'APPROVED' ? 'ok' : 'danger') : undefined,
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
    return ORDER.indexOf(simplifiedStatus(d.status)) > i;
  }

  protected statusTitle(status: string): string {
    return insuredStatusTitle(status);
  }

  protected description(status: string): string {
    return insuredStatusDescription(status);
  }

  protected simplifiedStatusLabel(status: string): string {
    return simplifiedStatusLabel(status);
  }

  // The badge shows the specific status, not the stepper's 3-step bucket.
  protected statusBadgeLabel(status: string): string {
    return insuredStatusBadgeLabel(status);
  }

  protected statusTone(status: string): StatusTone {
    const simple = simplifiedStatus(status);
    if (simple === 'FINISHED') {
      return status === 'APPROVED' ? 'ok' : 'danger';
    }
    return 'info';
  }

  protected reportedOn(c: CaseResponse): string {
    return c.createdAt ? new Date(c.createdAt).toLocaleDateString('es-AR') : '—';
  }
}
