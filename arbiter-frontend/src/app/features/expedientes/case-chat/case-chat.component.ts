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
import { interval } from 'rxjs';

import { CaseMessagesService } from '../case-messages.service';
import { CaseMessage, CaseMessageThread, MESSAGE_MAX_LENGTH } from '../../../core/models/case-message';
import { formatDateTime } from '../../../core/util/datetime';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { TextareaComponent } from '../../../shared/ui/textarea/textarea.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';

/** How often the thread is re-fetched while the screen is open. */
const POLL_MS = 15_000;

/**
 * A case thread, for both sides: the analyst tab and the insured portal.
 *
 * Polls instead of holding a connection open — decision #13 is stateless REST, and nothing here
 * justifies introducing WebSockets. Marks incoming messages read on load: the component only
 * exists while someone is looking at the thread.
 */
@Component({
  selector: 'app-case-chat',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CardComponent, ButtonComponent, TextareaComponent, InlineLoadingComponent],
  templateUrl: './case-chat.component.html',
  styleUrl: './case-chat.component.scss',
})
export class CaseChatComponent {
  private readonly service = inject(CaseMessagesService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);

  readonly caseId = input.required<number>();
  readonly insurer = input<string | null | undefined>(null);
  /** What the other side is called on screen; each portal names its counterpart differently. */
  readonly counterparty = input('Equipo de siniestros');
  readonly heading = input('Conversación');

  /** Lets the containing screen clear its unread marker. */
  readonly unreadChange = output<number>();

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
    () => this.canPost() && !this.sending() && this.draft().trim().length > 0,
  );

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
      const count = this.messages().length;
      untracked(() => {
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

  protected send(): void {
    const body = this.draft().trim();
    if (!body || this.sending()) {
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
      },
      error: (error: HttpErrorResponse) => {
        // The closed-thread 409 carries its own text, already written for the reader.
        this.sendError.set(
          error.error?.detail ?? 'No se pudo enviar el mensaje. Probá de nuevo en un momento.',
        );
        this.sending.set(false);
        // The window may have closed while they were typing.
        this.load(this.caseId(), false);
      },
    });
  }

  protected label(message: CaseMessage): string {
    return message.mine ? 'Vos' : this.counterparty();
  }

  protected when(message: CaseMessage): string {
    return formatDateTime(message.createdAt);
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
        this.unreadChange.emit(0);
      },
      // If marking read fails the thread still renders; the next poll fixes the count.
      error: () => undefined,
    });
  }

  private append(message: CaseMessage): void {
    this.thread.update((current) =>
      current ? { ...current, messages: [...current.messages, message] } : current,
    );
  }
}
