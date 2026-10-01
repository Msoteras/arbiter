import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { Subscription, interval } from 'rxjs';

import { CaseMessagesService } from '../case-messages.service';
import { CaseMessagesSocketService } from '../case-messages-socket.service';
import { RouterLink } from '@angular/router';
import {
  CaseMessage,
  CaseMessageEvent,
  CaseMessageThread,
  ChatEvent,
  MESSAGE_MAX_LENGTH,
} from '../../../core/models/case-message';
import { chatDayLabel, formatTime } from '../../../core/util/datetime';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { TextareaComponent } from '../../../shared/ui/textarea/textarea.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { QuickReply } from './quick-replies';

type ThreadRow =
  | { kind: 'day'; label: string }
  | { kind: 'msg'; message: CaseMessage }
  | { kind: 'event'; event: ChatEvent };

/**
 * Fallback only. Messages arrive over the socket; this covers the minutes after a deploy when the
 * front is new and the backend still isn't, and any network that blocks WebSockets outright.
 */
const POLL_MS = 60_000;

/**
 * A case thread, for both sides: the analyst's chat popup and Mensajes screen, and the insured portal.
 *
 * Messages arrive over a STOMP socket; the poll above is only a fallback. Sending stays on REST.
 * Marks incoming messages read on arrival: the component only exists while someone is looking.
 */
@Component({
  selector: 'app-case-chat',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, CardComponent, ButtonComponent, TextareaComponent, InlineLoadingComponent],
  host: { '[class.full-height]': 'fullHeight()' },
  templateUrl: './case-chat.component.html',
  styleUrl: './case-chat.component.scss',
})
export class CaseChatComponent {
  private readonly service = inject(CaseMessagesService);
  private readonly socket = inject(CaseMessagesSocketService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly caseId = input.required<number>();
  readonly insurer = input<string | null | undefined>(null);
  /** What the other side is called on screen; each portal names its counterpart differently. */
  readonly counterparty = input('Equipo de siniestros');
  readonly heading = input('Conversación');
  /** No card chrome and no `heading` — for a screen that already draws its own header/frame. */
  readonly bare = input(false);
  /** Stretches the thread to the container's height instead of a fixed `max-height`. */
  readonly fullHeight = input(false);
  readonly placeholder = input('Escribí tu mensaje…');
  readonly quickReplies = input<QuickReply[]>([]);
  readonly events = input<ChatEvent[]>([]);
  /** Shown before the time on the other side's messages ("Lucía · 12:02"); only the time if empty. */
  readonly authorName = input('');

  /** Lets the containing screen clear its unread marker. */
  readonly unreadChange = output<number>();
  /** The newest message, so a summary elsewhere on the screen stays in sync after a send. */
  readonly latest = output<CaseMessage | null>();

  protected readonly thread = signal<CaseMessageThread | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal(false);
  protected readonly draft = signal('');
  protected readonly sending = signal(false);
  protected readonly sendError = signal<string | null>(null);

  private readonly threadBox = viewChild<ElementRef<HTMLElement>>('threadBox');
  /** How close to the bottom still counts as "following the conversation". */
  private static readonly STICK_PX = 80;

  protected readonly maxLength = MESSAGE_MAX_LENGTH;
  protected readonly messages = computed(() => this.thread()?.messages ?? []);
  protected readonly canPost = computed(() => this.thread()?.canPost ?? false);
  protected readonly closedNotice = computed(() => this.thread()?.closedNotice ?? null);
  protected readonly canSend = computed(
    () =>
      this.canPost() &&
      !this.sending() &&
      this.draft().trim().length > 0 &&
      this.draft().length <= this.maxLength,
  );
  protected readonly nearLimit = computed(() => this.draft().length > this.maxLength * 0.9);

  protected readonly rows = computed<ThreadRow[]>(() => {
    const timeline = [
      ...this.messages().map((message) => ({
        at: message.createdAt,
        row: { kind: 'msg', message } as ThreadRow,
      })),
      ...this.events().map((event) => ({ at: event.at, row: { kind: 'event', event } as ThreadRow })),
    ].sort((a, b) => new Date(a.at).getTime() - new Date(b.at).getTime());

    const rows: ThreadRow[] = [];
    let lastDay = '';
    for (const { at, row } of timeline) {
      const day = chatDayLabel(at);
      if (day !== lastDay) {
        rows.push({ kind: 'day', label: day });
        lastDay = day;
      }
      rows.push(row);
    }
    return rows;
  });

  protected rowKey(row: ThreadRow): string {
    switch (row.kind) {
      case 'day':
        return `day-${row.label}`;
      case 'event':
        return `event-${row.event.at}-${row.event.label}`;
      default:
        return `msg-${row.message.id}`;
    }
  }

  constructor() {
    // untracked: reload per case, not on every draft keystroke or reply.
    effect(() => {
      const id = this.caseId();
      untracked(() => this.load(id, true));
    });

    // A conversation opens at its newest message, not its oldest. Only follows when the reader is
    // already at the bottom: yanking the scroll out from under someone reading back is worse than
    // making them scroll.
    effect(() => {
      const messages = this.messages();
      const count = messages.length;
      untracked(() => {
        if (this.thread()) {
          const newest = messages.at(-1) ?? null;
          this.latest.emit(newest);
          if (newest) {
            this.service.recordLatest(this.caseId(), this.insurer(), newest);
          }
        }
        if (count && (this.stickToBottom || this.atBottom())) {
          afterNextRender(() => this.scrollToBottom(), { injector: this.injector });
        }
        this.stickToBottom = false;
      });
    });

    interval(POLL_MS)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.load(this.caseId(), false));
  }

  /** Set when the move to the bottom shouldn't depend on where the reader was — first load, own send. */
  private stickToBottom = true;
  private watching: string | null = null;
  private socketSubscription?: Subscription;

  private atBottom(): boolean {
    const box = this.threadBox()?.nativeElement;
    if (!box) {
      return true;
    }
    return box.scrollHeight - box.scrollTop - box.clientHeight <= CaseChatComponent.STICK_PX;
  }

  private scrollToBottom(): void {
    const box = this.threadBox()?.nativeElement;
    if (box) {
      box.scrollTop = box.scrollHeight;
    }
  }

  /** Enter sends, Shift+Enter breaks the line. */
  protected onEnter(event: Event): void {
    const key = event as KeyboardEvent;
    if (key.shiftKey || key.isComposing) {
      return;
    }
    event.preventDefault();
    this.send();
  }

  protected useQuickReply(reply: QuickReply): void {
    this.draft.set(reply.text);
    this.focusField();
  }

  private focusField(): void {
    afterNextRender(
      () => {
        const field = this.host.nativeElement.querySelector('textarea');
        field?.focus();
        field?.setSelectionRange(field.value.length, field.value.length);
      },
      { injector: this.injector },
    );
  }

  protected send(): void {
    const body = this.draft().trim();
    if (!this.canSend()) {
      return;
    }
    this.sending.set(true);
    this.sendError.set(null);

    this.service.post(this.caseId(), body, this.insurer()).subscribe({
      next: (message) => {
        this.stickToBottom = true;
        this.append(message);
        this.draft.set('');
        this.sending.set(false);
        this.focusField();
      },
      error: (error: HttpErrorResponse) => {
        // The closed-thread 409 carries its own text, already written for the reader.
        this.sendError.set(
          error.error?.detail ?? 'No se pudo enviar el mensaje. Probá de nuevo en un momento.',
        );
        this.sending.set(false);
        this.focusField();
        // The window may have closed while they were typing.
        this.load(this.caseId(), false);
      },
    });
  }

  protected meta(message: CaseMessage): string {
    const time = formatTime(message.createdAt);
    if (!message.mine) {
      return this.authorName() ? `${this.authorName()} · ${time}` : time;
    }
    return message.readAt ? `Vos · ${time} · Leído` : `Vos · ${time}`;
  }

  protected authorLabel(message: CaseMessage): string {
    return message.mine ? 'Vos' : this.counterparty();
  }

  /** Opened after the first load: the destination comes with the thread, the client never builds it. */
  private listen(topic: string): void {
    if (this.watching === topic) {
      return;
    }
    this.watching = topic;
    this.socketSubscription?.unsubscribe();
    this.socketSubscription = this.socket
      .watch(topic)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((event) => this.receive(event));
  }

  /** A pushed message carries no `mine`: one payload goes to both sides, so it is placed here. */
  private receive(event: CaseMessageEvent): void {
    const current = this.thread();
    if (!current || current.messages.some((m) => m.id === event.id)) {
      return;
    }
    const mine = event.sender === current.viewerSide;
    this.append({
      id: event.id,
      sender: event.sender,
      mine,
      body: event.body,
      createdAt: event.createdAt,
      readAt: null,
    });
    if (!mine) {
      this.markRead(this.caseId());
    }
  }

  private load(caseId: number, first: boolean): void {
    if (!caseId) {
      return;
    }
    if (first) {
      this.loading.set(true);
    }
    this.service.thread(caseId, this.insurer()).subscribe({
      next: (thread) => {
        this.thread.set(thread);
        this.loading.set(false);
        this.loadError.set(false);
        this.listen(thread.topic);
        if (thread.unread > 0) {
          this.markRead(caseId);
        } else {
          this.unreadChange.emit(0);
        }
      },
      error: () => {
        this.loading.set(false);
        // A failed poll must not wipe what is already on screen; only the first load errors.
        if (first) {
          this.loadError.set(true);
        }
      },
    });
  }

  private markRead(caseId: number): void {
    this.service.markRead(caseId, this.insurer()).subscribe({
      next: () => {
        this.thread.update((current) => (current ? { ...current, unread: 0 } : current));
        this.service.clearUnread(caseId, this.insurer());
        this.unreadChange.emit(0);
      },
      // If marking read fails the thread still renders; the next poll fixes the count.
      error: () => undefined,
    });
  }

  private append(message: CaseMessage): void {
    this.thread.update((current) =>
      !current || current.messages.some((m) => m.id === message.id)
        ? current
        : { ...current, messages: [...current.messages, message] },
    );
  }
}
