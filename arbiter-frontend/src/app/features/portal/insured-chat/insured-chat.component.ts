import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  model,
  signal,
  untracked,
} from '@angular/core';

import { CaseMessageInboxItem, ChatEvent } from '../../../core/models/case-message';
import { ExpedienteResponse } from '../../../core/models/expediente';
import { movimientoAseguradoLabel } from '../../../core/models/estado';
import { CaseMessagesService } from '../../expedientes/case-messages.service';
import { ExpedienteService } from '../../expedientes/expediente.service';
import { CaseChatPopupComponent } from '../../expedientes/case-chat-popup/case-chat-popup.component';

/**
 * The insured's side of the case chat: talks to the analyst who holds the case, and interleaves the
 * claim's milestones. Built from the status, never the history `reason`, which is internal.
 */
export function insuredChatEvents(c: ExpedienteResponse): ChatEvent[] {
  const docsAction = {
    label: 'Ir a Mis documentos',
    link: ['/portal/cases', c.id, 'documents'],
    queryParams: c.insurerSlug ? { insurer: c.insurerSlug } : undefined,
  };
  return (
    (c.statusHistory ?? [])
      .map((h) => ({
        at: h.changedAt,
        toStatus: h.toStatus,
        label: movimientoAseguradoLabel(h.toStatus, h.fromStatus),
      }))
      .filter((e): e is { at: string; toStatus: string; label: string } => e.label !== null)
      // Consecutive repeats (e.g. classification retries) are one milestone.
      .filter((e, i, all) => i === all.length - 1 || all[i + 1].label !== e.label)
      .map((e) =>
        e.toStatus === 'AWAITING_DOCUMENTATION'
          ? {
              at: e.at,
              label: e.label,
              tone: c.status === 'AWAITING_DOCUMENTATION' ? ('warning' as const) : undefined,
              action: docsAction,
            }
          : { at: e.at, label: e.label },
      )
  );
}

export function analystFirstName(item: CaseMessageInboxItem | null): string | null {
  return item?.analystName?.trim().split(/\s+/)[0] ?? null;
}

/** "Lucía: …" / "Vos: …", or an invitation to write when the thread is empty. */
export function insuredPreview(item: CaseMessageInboxItem | null): string {
  if (!item?.lastMessageBody) {
    const analyst = analystFirstName(item);
    return analyst
      ? `Todavía no hay mensajes. Escribile a ${analyst}.`
      : 'Todavía no hay mensajes.';
  }
  const who = item.lastMessageSender === 'INSURED' ? 'Vos' : (analystFirstName(item) ?? 'Equipo');
  return `${who}: ${item.lastMessageBody}`;
}

export function initialsOf(name: string | null | undefined): string {
  const parts = (name ?? '').trim().split(/\s+/).filter(Boolean);
  return `${parts[0]?.[0] ?? ''}${parts[1]?.[0] ?? ''}`.toUpperCase();
}

@Component({
  selector: 'app-insured-chat',
  imports: [CaseChatPopupComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-case-chat-popup
      [(open)]="open"
      [caseId]="caseId()"
      [insurer]="insurer()"
      [counterparty]="counterparty()"
      [subtitle]="subtitle()"
      [authorName]="authorName()"
      [events]="events()"
      [unread]="unread()"
      [launcher]="launcher()"
    />
  `,
})
export class InsuredChatComponent {
  private readonly messages = inject(CaseMessagesService);
  private readonly expedientes = inject(ExpedienteService);

  readonly open = model(false);
  readonly caseId = input.required<number>();
  readonly insurer = input<string | null | undefined>(null);
  /** Pass it when the page already has the case; otherwise it's fetched on open. */
  readonly caseData = input<ExpedienteResponse | null>(null);
  readonly launcher = input(false);

  private readonly fetched = signal<ExpedienteResponse | null>(null);
  private readonly detail = computed(() => this.caseData() ?? this.fetched());
  private readonly inboxItem = computed(() =>
    this.messages.inboxItem(this.caseId(), this.insurer()),
  );

  private readonly analystName = computed(
    () => this.detail()?.assignedAnalystName ?? this.inboxItem()?.analystName ?? null,
  );
  protected readonly counterparty = computed(() => this.analystName() ?? 'Equipo de siniestros');
  protected readonly authorName = computed(() => this.analystName()?.split(/\s+/)[0] ?? '');

  protected readonly subtitle = computed(() => {
    const insurerName = this.detail()?.insurerName ?? this.inboxItem()?.insurerName;
    const where = insurerName
      ? this.analystName()
        ? `Analista de ${insurerName}`
        : insurerName
      : null;
    return [where, `Siniestro #${this.caseId()}`].filter(Boolean).join(' · ');
  });

  protected readonly events = computed(() => {
    const d = this.detail();
    return d ? insuredChatEvents(d) : [];
  });

  protected readonly unread = computed(() => this.inboxItem()?.unreadCount ?? 0);

  constructor() {
    effect(() => {
      const open = this.open();
      const id = this.caseId();
      untracked(() => {
        const current = this.fetched();
        const stale = current?.id !== id || current?.insurerSlug !== this.insurer();
        if (open && !this.caseData() && stale) {
          this.fetched.set(null);
          this.expedientes.getById(id, this.insurer()).subscribe({
            next: (data) => this.fetched.set(data),
            error: () => undefined,
          });
        }
      });
    });
  }
}
