import { ChangeDetectionStrategy, Component, computed, input, model, output } from '@angular/core';
import { RouterLink } from '@angular/router';

import { CaseMessage, ChatEvent } from '../../../core/models/case-message';
import { CaseChatComponent } from '../case-chat/case-chat.component';
import { QuickReply } from '../case-chat/quick-replies';
import { ButtonComponent } from '../../../shared/ui/button/button.component';

/**
 * The case chat as a floating window, for both sides, with its launcher when closed. The thread only
 * mounts while open: mounting it is what marks the other side's messages as read.
 */
@Component({
  selector: 'app-case-chat-popup',
  imports: [RouterLink, CaseChatComponent, ButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown.escape)': 'open.set(false)' },
  template: `
    @if (open()) {
      <section class="popup" role="dialog" [attr.aria-label]="'Chat con ' + counterparty()">
        <header class="head">
          <span class="avatar" aria-hidden="true">{{ initials() }}</span>
          <div class="title">
            <p class="name">{{ counterparty() }}</p>
            @if (subtitle()) {
              <p class="sub">{{ subtitle() }}</p>
            }
          </div>
          @if (expandLink(); as link) {
            <app-button variant="secondary" size="sm" [routerLink]="link" (click)="open.set(false)">
              Expandir
            </app-button>
          }
          <button type="button" class="close" aria-label="Cerrar chat" (click)="open.set(false)">
            ✕
          </button>
        </header>
        <app-case-chat
          class="thread"
          [caseId]="caseId()"
          [insurer]="insurer()"
          [counterparty]="counterparty()"
          [authorName]="authorName()"
          [placeholder]="placeholder()"
          [quickReplies]="quickReplies()"
          [events]="events()"
          [bare]="true"
          [fullHeight]="true"
          (unreadChange)="unreadChange.emit($event)"
          (latest)="latest.emit($event)"
        />
      </section>
    } @else if (launcher()) {
      <button
        type="button"
        class="launcher"
        [class.round]="!launcherLabel()"
        [attr.aria-label]="launcherLabel() ? null : 'Abrir chat con ' + counterparty()"
        (click)="open.set(true)"
      >
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.8"
          aria-hidden="true"
        >
          <path
            d="M21 12a8 8 0 0 1-11.6 7.1L4 20.5l1.4-4.9A8 8 0 1 1 21 12z"
            stroke-linejoin="round"
          />
        </svg>
        {{ launcherLabel() }}
        @if (unread() > 0) {
          <span class="launcher-count">{{ unread() }}</span>
          <span class="sr-only">mensajes sin leer</span>
        }
      </button>
    }
  `,
  styles: `
    :host {
      position: fixed;
      right: var(--space-5);
      bottom: var(--space-5);
      z-index: 50;
    }

    .popup {
      display: flex;
      flex-direction: column;
      width: 400px;
      height: min(600px, calc(100vh - var(--appbar-h) - var(--space-7)));
      overflow: hidden;
      border: 1px solid var(--border-default);
      border-radius: var(--radius-modal);
      background: var(--surface);
      box-shadow: var(--shadow-pop);
      animation: pop-in var(--dur-3) var(--ease-out);
    }

    .head {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      padding: var(--space-3) var(--space-4);
      border-bottom: 1px solid var(--border-subtle);
    }

    .avatar {
      display: flex;
      align-items: center;
      justify-content: center;
      flex: none;
      width: 36px;
      height: 36px;
      border-radius: var(--radius-pill);
      background: var(--selected-bg);
      color: var(--accent-fg);
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-medium);
    }

    .title {
      flex: 1;
      min-width: 0;
    }
    .name {
      margin: 0;
      font-weight: var(--font-weight-medium);
      color: var(--text-primary);
    }
    .sub {
      margin: 0;
      overflow: hidden;
      font-size: var(--font-size-2xs);
      color: var(--text-muted);
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .close {
      flex: none;
      padding: var(--space-1);
      border: none;
      border-radius: var(--radius-ctl);
      background: none;
      color: var(--text-tertiary);
      cursor: pointer;
    }
    .close:hover {
      color: var(--text-primary);
    }
    .close:focus-visible {
      outline: 2px solid var(--border-focus);
      outline-offset: 2px;
    }

    .thread {
      flex: 1;
      min-height: 0;
    }

    .launcher {
      display: inline-flex;
      align-items: center;
      gap: var(--space-2);
      padding: var(--space-3) var(--space-5);
      border: none;
      border-radius: var(--radius-pill);
      background: var(--action-accent-bg);
      color: var(--action-accent-fg);
      font: inherit;
      font-weight: var(--font-weight-medium);
      box-shadow: var(--shadow-pop);
      cursor: pointer;
    }
    .launcher:hover {
      background: var(--action-accent-bg-hover);
    }
    .launcher:focus-visible {
      outline: none;
      box-shadow: var(--focus-ring), var(--shadow-pop);
    }
    .launcher svg {
      width: 20px;
      height: 20px;
    }
    .launcher.round {
      position: relative;
      justify-content: center;
      width: 56px;
      height: 56px;
      padding: 0;
    }
    .launcher.round svg {
      width: 24px;
      height: 24px;
    }
    .launcher.round .launcher-count {
      position: absolute;
      top: 0;
      right: 0;
      border: 2px solid var(--action-accent-bg);
    }
    .launcher-count {
      min-width: var(--space-4);
      padding: 0 var(--space-1);
      border-radius: var(--radius-pill);
      background: var(--action-accent-fg);
      color: var(--action-accent-bg);
      font-size: var(--font-size-2xs);
      font-weight: var(--font-weight-bold);
      line-height: var(--space-4);
      text-align: center;
      font-variant-numeric: tabular-nums;
    }

    @keyframes pop-in {
      from {
        opacity: 0;
        transform: translateY(var(--space-3));
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .popup {
        animation: none;
      }
    }

    @media (max-width: 640px) {
      :host:has(.popup) {
        inset: var(--appbar-h) 0 0 0;
      }
      .popup {
        width: 100%;
        height: 100%;
        border-radius: 0;
      }
      :host:not(:has(.popup)) {
        right: var(--space-4);
        bottom: var(--space-4);
      }
    }
  `,
})
export class CaseChatPopupComponent {
  readonly open = model(false);
  readonly caseId = input.required<number>();
  readonly insurer = input<string | null | undefined>(null);
  readonly counterparty = input('Asegurado');
  readonly subtitle = input('');
  /** Null hides "Expandir": the insured has no full-screen conversation view. */
  readonly expandLink = input<(string | number)[] | null>(null);
  readonly placeholder = input('Escribí tu mensaje…');
  readonly authorName = input('');
  readonly quickReplies = input<QuickReply[]>([]);
  readonly events = input<ChatEvent[]>([]);
  readonly unread = input(0);
  /** Off when the page opens the popup from its own button and needs no floating launcher. */
  readonly launcher = input(true);
  /** Empty renders the round, icon-only launcher. */
  readonly launcherLabel = input('');

  readonly unreadChange = output<number>();
  readonly latest = output<CaseMessage | null>();

  protected readonly initials = computed(() => {
    const parts = this.counterparty().trim().split(/\s+/);
    return `${parts[0]?.[0] ?? ''}${parts[1]?.[0] ?? ''}`.toUpperCase() || '—';
  });
}
