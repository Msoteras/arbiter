import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterLink } from '@angular/router';
import { distinctUntilChanged, filter, map, startWith } from 'rxjs';

import { CaseMessagesService } from '../cases/case-messages.service';
import { CaseService } from '../cases/case.service';
import { CaseChatComponent } from '../cases/case-chat/case-chat.component';
import { analystQuickReplies } from '../cases/case-chat/quick-replies';
import { chatListStamp } from '../../core/util/datetime';
import { CaseResponse } from '../../core/models/case';
import { caseStatusLabel, caseStatusTone } from '../../core/models/case-status';
import { StatusTone } from '../../core/models/status-tone';
import { CardComponent } from '../../shared/ui/card/card.component';
import { BadgeComponent } from '../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import { InputComponent } from '../../shared/ui/input/input.component';
import { EmptyStateComponent } from '../../shared/ui/empty-state/empty-state.component';
import { InlineLoadingComponent } from '../../shared/ui/inline-loading/inline-loading.component';

type InboxFilter = 'ALL' | 'UNREAD' | 'WAITING';

@Component({
  selector: 'app-messages',
  imports: [
    RouterLink,
    CaseChatComponent,
    CardComponent,
    BadgeComponent,
    ButtonComponent,
    InputComponent,
    EmptyStateComponent,
    InlineLoadingComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './messages.component.html',
  styleUrl: './messages.component.scss',
})
export class MessagesComponent {
  private readonly service = inject(CaseMessagesService);
  private readonly caseService = inject(CaseService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  // Only the first load shows the spinner; later refreshes update the list in place.
  protected readonly loading = signal(this.service.inboxItems().length === 0);
  protected readonly loadError = signal(false);
  protected readonly items = computed(() =>
    [...this.service.inboxItems()].sort((a, b) =>
      (b.lastMessageAt ?? '').localeCompare(a.lastMessageAt ?? ''),
    ),
  );
  protected readonly search = signal('');
  protected readonly filter = signal<InboxFilter>('ALL');

  /** The conversation lives in a componentless child route, so it's read from there. */
  protected readonly selectedCaseId = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      startWith(null),
      map(() => {
        const id = this.route.firstChild?.snapshot.paramMap.get('caseId');
        return id ? Number(id) : null;
      }),
      distinctUntilChanged(),
    ),
    { initialValue: null },
  );

  protected readonly selected = computed(
    () => this.items().find((i) => i.caseId === this.selectedCaseId()) ?? null,
  );

  protected readonly selectedDetail = signal<CaseResponse | null>(null);

  protected readonly unreadTotal = computed(
    () => this.items().filter((i) => i.unreadCount > 0).length,
  );
  protected readonly waitingCount = computed(
    () => this.items().filter((i) => i.lastMessageSender === 'INSURED').length,
  );

  protected readonly filtered = computed(() => {
    const q = this.search().trim().toLowerCase();
    return this.items().filter((item) => {
      if (this.filter() === 'UNREAD' && item.unreadCount === 0) return false;
      if (this.filter() === 'WAITING' && item.lastMessageSender !== 'INSURED') return false;
      if (!q) return true;
      return (
        item.insuredName.toLowerCase().includes(q) ||
        item.claimCause.toLowerCase().includes(q) ||
        String(item.caseId).includes(q)
      );
    });
  });

  constructor() {
    this.load();

    // Fetches the case only for the panel to the right of the thread; the chat itself only needs caseId.
    effect(() => {
      const id = this.selectedCaseId();
      untracked(() => {
        this.selectedDetail.set(null);
        if (id) {
          this.caseService.getById(id).subscribe({
            next: (data) => this.selectedDetail.set(data),
            error: () => undefined,
          });
        }
      });
    });
  }

  private load(): void {
    this.service.inbox().subscribe({
      next: () => {
        this.loading.set(false);
        this.loadError.set(false);
      },
      error: () => {
        this.loading.set(false);
        // A failed refresh keeps the list already on screen.
        this.loadError.set(this.service.inboxItems().length === 0);
      },
    });
  }

  protected select(caseId: number): void {
    this.router.navigate(['/messages', caseId]);
  }

  protected initials(name: string): string {
    const parts = name.trim().split(/\s+/);
    return `${parts[0]?.[0] ?? ''}${parts[1]?.[0] ?? ''}`.toUpperCase() || '—';
  }

  protected firstName(name: string): string {
    return name.trim().split(/\s+/)[0] ?? '';
  }

  protected readonly quickReplies = computed(() =>
    analystQuickReplies(this.selected()?.insuredName ?? ''),
  );

  protected readonly stamp = chatListStamp;

  protected readonly caseStatusLabel = caseStatusLabel;

  protected tono(status: string): StatusTone {
    return caseStatusTone(status);
  }
}
