import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, combineLatest, map, of, startWith, switchMap } from 'rxjs';

import { InsuredSessionService } from '../../../core/auth/insured-session.service';
import { CaseResponse } from '../../../core/models/case';
import {
  InsuredMovement,
  insuredMovementLabel,
  insuredStatusDescription,
  insuredStatusTitle,
  insuredStatusTone,
  isFinalInsuredStatus,
} from '../../../core/models/insured-status';
import { StatusTone } from '../../../core/models/status-tone';
import { formatDateTime } from '../../../core/util/datetime';
import { CaseService } from '../../cases/case.service';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CaseDocumentsComponent } from '../../cases/case-documents/case-documents.component';
import { CaseMessagesService } from '../../cases/case-messages.service';
import { InsurerLogoService } from '../../../core/services/insurer-logo.service';
import { SkeletonComponent } from '../../../shared/ui/skeleton/skeleton.component';
import {
  InsuredChatComponent,
  initialsOf,
  insuredPreview,
} from '../insured-chat/insured-chat.component';

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; data: CaseResponse }
  | { status: 'error'; httpStatus: number };

interface Movement {
  label: string;
  date: string;
  current: boolean;
}

/** Insured-facing case view: never shows the model's classification or recommendation. */
@Component({
  selector: 'app-case-tracking',
  imports: [
    RouterLink,
    CardComponent,
    ButtonComponent,
    CaseDocumentsComponent,
    InsuredChatComponent,
    SkeletonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './case-tracking.component.html',
  styleUrl: './case-tracking.component.scss',
})
export class CaseTrackingComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly service = inject(CaseService);
  private readonly session = inject(InsuredSessionService);
  private readonly messages = inject(CaseMessagesService);
  private readonly logos = inject(InsurerLogoService);

  protected readonly chatOpen = signal(false);

  // Case ids repeat across insurers, so the `insurer` query param is needed to resolve the tenant.
  private readonly state = toSignal(
    combineLatest([this.route.paramMap, this.route.queryParamMap]).pipe(
      map(([params, query]) => ({
        id: params.get('id') ?? '',
        insurer: query.get('insurer'),
      })),
      switchMap(({ id, insurer }) =>
        this.service.getById(id, insurer).pipe(
          map((data): LoadState => ({ status: 'ok', data })),
          startWith<LoadState>({ status: 'loading' }),
          catchError((err: HttpErrorResponse) =>
            of<LoadState>({ status: 'error', httpStatus: err.status }),
          ),
        ),
      ),
    ),
    { initialValue: { status: 'loading' } as LoadState },
  );

  protected readonly loading = computed(() => this.state().status === 'loading');
  protected readonly hasError = computed(() => this.state().status === 'error');

  protected readonly data = computed<CaseResponse | null>(() => {
    const s = this.state();
    if (s.status !== 'ok') {
      return null;
    }
    // UX courtesy only: the backend enforces access.
    const sessionId = this.session.insuredId();
    if (sessionId && s.data.insuredId !== sessionId) {
      return null;
    }
    return s.data;
  });

  /** The logo of the insurer this claim belongs to; the insured may have several. */
  protected readonly insurerLogo = toSignal(
    toObservable(computed(() => this.data()?.insurerSlug ?? null)).pipe(
      switchMap((slug) => (slug ? this.logos.forInsurer(slug) : of(null))),
    ),
    { initialValue: null },
  );

  protected readonly notFound = computed(() => {
    const s = this.state();
    return (s.status === 'error' && s.httpStatus === 404) || (s.status === 'ok' && !this.data());
  });

  private readonly status = computed(() => this.data()?.insuredStatus.code ?? null);

  private readonly history = computed<InsuredMovement[]>(() => this.data()?.statusHistory ?? []);

  protected readonly heroTitle = computed(() => {
    const code = this.status();
    return code ? insuredStatusTitle(code, this.history()) : '';
  });

  // The filing is always behind: the insured is never told the case is still being classified.
  protected readonly progressSteps = ['Denunciado', 'En trámite', 'Terminado'] as const;

  protected readonly progressIndex = computed(() => (this.isResolved() ? 2 : 1));

  protected readonly statusTone = computed<StatusTone>(() => {
    const code = this.status();
    return code ? insuredStatusTone(code) : 'neutral';
  });

  protected readonly statusDescription = computed(() => {
    const code = this.status();
    return code ? insuredStatusDescription(code, this.history()) : '';
  });

  protected readonly isResolved = computed(() => {
    const code = this.status();
    return code ? isFinalInsuredStatus(code) : false;
  });

  protected readonly needsDocs = computed(() => this.status() === 'AWAITING_DOCUMENTATION');

  // Unlike the expert assessment, the repair provider is disclosed to the insured. The status
  // check avoids showing a stale value once the case is back from repair.
  protected readonly technicalService = computed(() =>
    this.status() === 'UNDER_REPAIR' ? (this.data()?.repairProvider ?? null) : null,
  );

  protected readonly movements = computed<Movement[]>(() => {
    const history = this.history();
    const repairProviderName = this.data()?.repairProvider?.name ?? null;
    return history.map((h, i) => ({
      label: insuredMovementLabel(h, repairProviderName),
      date: formatDateTime(h.changedAt, ''),
      current: i === history.length - 1,
    }));
  });

  protected readonly chatItem = computed(() => {
    const d = this.data();
    return d ? this.messages.inboxItem(d.id, d.insurerSlug) : null;
  });
  protected readonly chatPreview = computed(() => insuredPreview(this.chatItem()));
  protected readonly chatUnread = computed(() => this.chatItem()?.unreadCount ?? 0);
  protected readonly analystInitials = computed(() => initialsOf(this.data()?.assignedAnalystName));

  protected readonly reportedOn = computed(() => formatDateTime(this.data()?.createdAt));

  protected readonly occurredOn = computed(() => formatDateTime(this.data()?.eventDate));

  protected readonly policeReportOn = computed(() => {
    const at = this.data()?.policeReportAt;
    return at ? formatDateTime(at) : null;
  });

  protected goToDocuments(): void {
    // Forward `insurer`, or the documents screen resolves the case against the default tenant.
    const insurer = this.route.snapshot.queryParamMap.get('insurer');
    this.router.navigate(['documents'], {
      relativeTo: this.route,
      queryParams: insurer ? { insurer } : {},
    });
  }
}
