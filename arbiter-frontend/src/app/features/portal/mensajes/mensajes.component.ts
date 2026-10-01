import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { CaseMessageInboxItem } from '../../../core/models/case-message';
import { isEstadoFinal } from '../../../core/models/estado';
import { chatListStamp } from '../../../core/util/datetime';
import { CaseMessagesService } from '../../expedientes/case-messages.service';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import {
  InsuredChatComponent,
  initialsOf,
  insuredPreview,
} from '../insured-chat/insured-chat.component';

/** One conversation per claim; opening a row opens that claim's chat in place. */
@Component({
  selector: 'app-mensajes',
  imports: [
    BadgeComponent,
    CardComponent,
    EmptyStateComponent,
    InlineLoadingComponent,
    InsuredChatComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="portal">
      <header class="portal-head">
        <h1 class="t-hero-title">Mensajes</h1>
      </header>

      @if (loading()) {
        <app-inline-loading message="Cargando tus conversaciones…" />
      } @else if (loadError()) {
        <app-card>
          <p class="t-note">No pudimos cargar tus mensajes. Probá de nuevo en unos minutos.</p>
        </app-card>
      } @else if (items().length === 0) {
        <app-empty-state
          message="Todavía no tenés siniestros"
          sub="Cuando denuncies uno, vas a poder hablar con tu analista desde acá."
        />
      } @else {
        <app-card [flush]="true">
          <ul class="rows">
            @for (item of items(); track item.insurerSlug + '-' + item.caseId) {
              <li>
                <button
                  type="button"
                  class="row"
                  [class.unread]="item.unreadCount > 0"
                  (click)="open(item)"
                >
                  <span class="avatar" [class.closed]="isClosed(item)" aria-hidden="true">{{
                    initials(item) || 'ES'
                  }}</span>
                  <span class="main">
                    <span class="title-row">
                      <span class="title">{{ item.claimCause }}</span>
                      <span class="mono case-id">EXP-{{ item.caseId }}</span>
                      @if (item.insurerName) {
                        <app-badge>{{ item.insurerName }}</app-badge>
                      }
                    </span>
                    <span class="preview">{{ preview(item) }}</span>
                  </span>
                  <span class="side">
                    @if (item.lastMessageAt) {
                      <span class="time tabular">{{ stamp(item.lastMessageAt) }}</span>
                    }
                    @if (item.unreadCount > 0) {
                      <span class="count">{{ item.unreadCount }}</span>
                      <span class="sr-only">sin leer</span>
                    }
                  </span>
                </button>
              </li>
            }
          </ul>
        </app-card>
      }
    </div>

    @if (selected(); as s) {
      <app-insured-chat [(open)]="chatOpen" [caseId]="s.caseId" [insurer]="s.insurerSlug" />
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .portal {
      display: flex;
      flex-direction: column;
      gap: var(--space-4);
      max-width: 960px;
      margin: 0 auto;
      padding: var(--space-5) var(--space-6) var(--space-7);
    }
    .portal-head h1 {
      margin: 0;
    }
    .rows {
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .rows li + li {
      border-top: 1px solid var(--border-subtle);
    }
    .row {
      display: flex;
      align-items: center;
      gap: var(--space-4);
      width: 100%;
      padding: var(--space-4);
      border: none;
      background: none;
      font: inherit;
      text-align: left;
      cursor: pointer;
    }
    .row:hover {
      background: var(--surface-soft);
    }
    .row:focus-visible {
      outline: none;
      box-shadow: inset var(--focus-ring);
    }
    .avatar {
      display: inline-flex;
      flex: none;
      align-items: center;
      justify-content: center;
      width: 40px;
      height: 40px;
      border-radius: var(--radius-pill);
      background: var(--action-accent-bg);
      color: var(--action-accent-fg);
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-medium);
    }
    .avatar.closed {
      background: var(--surface-head);
      color: var(--text-tertiary);
    }
    .main {
      display: flex;
      flex: 1;
      flex-direction: column;
      gap: var(--space-1);
      min-width: 0;
    }
    .title-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2);
    }
    .title {
      color: var(--text-primary);
    }
    .row.unread .title {
      font-weight: var(--font-weight-bold);
    }
    .case-id {
      font-size: var(--font-size-2xs);
      color: var(--text-muted);
    }
    .preview {
      overflow: hidden;
      color: var(--text-tertiary);
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .row.unread .preview {
      color: var(--text-primary);
    }
    .side {
      display: flex;
      flex: none;
      flex-direction: column;
      align-items: flex-end;
      gap: var(--space-1);
    }
    .time {
      font-size: var(--font-size-2xs);
      color: var(--text-muted);
    }
    .count {
      min-width: var(--space-4);
      padding: 0 var(--space-1);
      border-radius: var(--radius-pill);
      background: var(--action-accent-bg);
      color: var(--action-accent-fg);
      font-size: var(--font-size-2xs);
      font-weight: var(--font-weight-bold);
      line-height: var(--space-4);
      text-align: center;
      font-variant-numeric: tabular-nums;
    }
    @media (max-width: 640px) {
      .portal {
        padding: var(--space-4);
      }
    }
  `,
})
export class MensajesComponent {
  private readonly messages = inject(CaseMessagesService);

  protected readonly loading = signal(true);
  protected readonly loadError = signal(false);
  protected readonly items = this.messages.inboxItems;

  protected readonly chatOpen = signal(false);
  private readonly selectedKey = signal<{ caseId: number; insurer: string | null } | null>(null);
  protected readonly selected = computed(() => {
    const key = this.selectedKey();
    return key ? this.messages.inboxItem(key.caseId, key.insurer) : null;
  });

  constructor() {
    this.messages.inbox().subscribe({
      next: () => this.loading.set(false),
      error: () => {
        this.loading.set(false);
        this.loadError.set(true);
      },
    });
  }

  protected open(item: CaseMessageInboxItem): void {
    this.selectedKey.set({ caseId: item.caseId, insurer: item.insurerSlug });
    this.chatOpen.set(true);
  }

  protected initials(item: CaseMessageInboxItem): string {
    return initialsOf(item.analystName);
  }

  protected isClosed(item: CaseMessageInboxItem): boolean {
    return isEstadoFinal(item.status);
  }

  protected readonly preview = insuredPreview;
  protected readonly stamp = chatListStamp;
}
