import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { map } from 'rxjs';

import { CaseMessagesService } from '../expedientes/case-messages.service';
import { ExpedienteService } from '../expedientes/expediente.service';
import { CaseChatComponent } from '../expedientes/case-chat/case-chat.component';
import { analystQuickReplies } from '../expedientes/case-chat/quick-replies';
import { CaseMessage, CaseMessageInboxItem } from '../../core/models/case-message';
import { chatListStamp } from '../../core/util/datetime';
import { ExpedienteResponse } from '../../core/models/expediente';
import { estadoLabel, estadoTone } from '../../core/models/estado';
import { StatusTone } from '../../core/models/status-tone';
import { CardComponent } from '../../shared/ui/card/card.component';
import { BadgeComponent } from '../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import { InputComponent } from '../../shared/ui/input/input.component';
import { EmptyStateComponent } from '../../shared/ui/empty-state/empty-state.component';
import { InlineLoadingComponent } from '../../shared/ui/inline-loading/inline-loading.component';

type Filtro = 'TODOS' | 'SIN_LEER' | 'ESPERANDO';

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
  private readonly expedientes = inject(ExpedienteService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly loading = signal(true);
  protected readonly loadError = signal(false);
  protected readonly items = signal<CaseMessageInboxItem[]>([]);
  protected readonly search = signal('');
  protected readonly filtro = signal<Filtro>('TODOS');

  protected readonly selectedCaseId = toSignal(
    this.route.paramMap.pipe(map((params) => (params.get('caseId') ? Number(params.get('caseId')) : null))),
    { initialValue: null },
  );

  protected readonly selected = computed(
    () => this.items().find((i) => i.caseId === this.selectedCaseId()) ?? null,
  );

  protected readonly selectedDetail = signal<ExpedienteResponse | null>(null);

  protected readonly sinLeerCount = computed(() => this.items().filter((i) => i.unreadCount > 0).length);
  protected readonly esperandoCount = computed(
    () => this.items().filter((i) => i.lastMessageSender === 'INSURED').length,
  );

  protected readonly filtered = computed(() => {
    const q = this.search().trim().toLowerCase();
    return this.items().filter((item) => {
      if (this.filtro() === 'SIN_LEER' && item.unreadCount === 0) return false;
      if (this.filtro() === 'ESPERANDO' && item.lastMessageSender !== 'INSURED') return false;
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
          this.expedientes.getById(id).subscribe({
            next: (data) => this.selectedDetail.set(data),
            error: () => undefined,
          });
        }
      });
    });
  }

  private load(): void {
    this.loading.set(true);
    this.service.inbox().subscribe({
      next: (items) => {
        this.items.set(items);
        this.loading.set(false);
        this.loadError.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.loadError.set(true);
      },
    });
  }

  protected select(caseId: number): void {
    this.router.navigate(['/messages', caseId]);
  }

  /** The thread already fetched and marked read; only the row's own count needs to catch up. */
  protected onUnreadChange(caseId: number, count: number): void {
    this.items.update((items) =>
      items.map((item) => (item.caseId === caseId ? { ...item, unreadCount: count } : item)),
    );
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

  /** Keeps the row's preview and order in step with what was just sent or received. */
  protected onLatest(caseId: number, message: CaseMessage | null): void {
    if (!message) return;
    this.items.update((items) =>
      items
        .map((item) =>
          item.caseId === caseId
            ? {
                ...item,
                lastMessageBody: message.body,
                lastMessageSender: message.sender,
                lastMessageAt: message.createdAt,
              }
            : item,
        )
        .sort((a, b) => b.lastMessageAt.localeCompare(a.lastMessageAt)),
    );
  }

  protected readonly stamp = chatListStamp;

  protected readonly estadoLabel = estadoLabel;

  protected tono(status: string): StatusTone {
    return estadoTone(status);
  }
}
