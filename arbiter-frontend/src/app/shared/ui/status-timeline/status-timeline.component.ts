import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { StatusTransition } from '../../../core/models/case';
import {
  CaseStatus,
  caseStatusLabel,
  caseStatusTone,
  isFinalStatus,
} from '../../../core/models/case-status';
import { historyNote } from '../../../core/models/history-note';
import { StatusTone } from '../../../core/models/status-tone';
import { formatDateTime } from '../../../core/util/datetime';
import { BadgeComponent } from '../badge/badge.component';

const ACTOR_LABELS: Record<StatusTransition['actor'], string> = {
  SYSTEM: 'Sistema',
  INSURED: 'Asegurado',
  ANALYST: 'Analista',
  REFERENT: 'Referente',
};

/**
 * Analyst-facing wording. `proximoPaso()` in core/models/estado addresses the insured, whose portal
 * has its own timeline.
 */
const ANALYST_NEXT_STEP: Partial<Record<CaseStatus, string>> = {
  PENDING_CLASSIFICATION: 'El motor de reglas y el modelo todavía están evaluando el caso.',
  AWAITING_DOCUMENTATION: 'Esperando que el asegurado suba la documentación que falta.',
  CLASSIFICATION_FAILED: 'Podés reintentar la clasificación desde la card de arriba.',
  PENDING_EXPERT_REPORT: 'Esperando el informe del perito para volver a revisión.',
  PENDING_REPAIR: 'Esperando la respuesta del servicio técnico para volver a revisión.',
  // No PENDING_ANALYST_REVIEW entry: the decision card already prompts the analyst.
};

/** Case status history; an unresolved case ends with a placeholder step for the expected next one. */
@Component({
  selector: 'app-status-timeline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [BadgeComponent],
  template: `
    <ol class="timeline">
      @for (h of history(); track $index; let last = $last) {
        <li
          class="step"
          [class.current]="last && !hasNextStep()"
          [attr.data-tone]="
            last && !hasNextStep() && currentTone() !== 'neutral' ? currentTone() : null
          "
        >
          <span class="marker" aria-hidden="true"></span>
          <div class="body">
            <div class="when mono">{{ when(h.changedAt) }}</div>
            <div class="transition">
              @if (isSameStatus(h)) {
                <span class="from">Sin cambio de estado</span>
              } @else {
                @if (h.fromStatus) {
                  <span class="from">{{ statusLabel(h.fromStatus) }}</span>
                  <span class="arrow" aria-hidden="true">→</span>
                }
                @if (last && !hasNextStep()) {
                  <app-badge variant="strong" [tone]="currentTone()">{{
                    statusLabel(h.toStatus)
                  }}</app-badge>
                } @else {
                  <app-badge>{{ statusLabel(h.toStatus) }}</app-badge>
                }
              }
            </div>
            <div class="meta">
              <span class="actor">{{ actor(h.actor) }}</span>
              <span class="reason">{{ reasonNote(h.reason) }}</span>
            </div>
            @if (h.observation) {
              <q class="observation">{{ h.observation }}</q>
            }
          </div>
        </li>
      }
      @if (hasNextStep()) {
        <li class="step next">
          <span class="marker" aria-hidden="true"></span>
          <div class="body">
            <div class="when">Próximo paso esperado</div>
            <p class="next-text">{{ nextStep() }}</p>
          </div>
        </li>
      }
    </ol>
    @if (history().length === 0) {
      <p class="empty">Todavía no hay movimientos registrados.</p>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .timeline {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .step {
      position: relative;
      padding: 0 0 var(--space-4) var(--space-5);
    }
    .step:not(:last-child)::before {
      content: '';
      position: absolute;
      left: 6px;
      top: 16px;
      bottom: -2px;
      width: 1px;
      background: var(--border-strong);
    }
    .marker {
      position: absolute;
      left: 0;
      top: 3px;
      width: 13px;
      height: 13px;
      border-radius: var(--radius-pill);
      background: var(--surface);
      border: 2px solid var(--border-strong);
    }
    .step.current .marker {
      background: var(--accent);
      border-color: var(--accent);
    }
    .step.current[data-tone='ok'] .marker {
      background: var(--status-ok);
      border-color: var(--status-ok);
    }
    .step.current[data-tone='warning'] .marker {
      background: var(--status-warning);
      border-color: var(--status-warning);
    }
    .step.current[data-tone='danger'] .marker {
      background: var(--status-danger);
      border-color: var(--status-danger);
    }
    .step.current[data-tone='info'] .marker {
      background: var(--status-info);
      border-color: var(--status-info);
    }
    .step.next .marker {
      border-style: dashed;
      border-color: var(--text-muted);
    }
    .step.next::before {
      display: none;
    }

    .when {
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      margin-bottom: 3px;
    }
    .transition {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      flex-wrap: wrap;
    }
    .from {
      font-size: var(--font-size-sm);
      color: var(--text-muted);
    }
    .arrow {
      color: var(--text-muted);
    }
    .meta {
      margin-top: var(--space-1);
      display: flex;
      gap: var(--space-2);
      align-items: baseline;
      flex-wrap: wrap;
    }
    .actor {
      font-size: var(--font-size-2xs);
      font-weight: var(--font-weight-medium);
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: var(--text-tertiary);
    }
    .reason {
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
    }
    .observation {
      display: block;
      margin-top: var(--space-1);
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
      font-style: italic;
    }
    .step.next .when {
      text-transform: uppercase;
      letter-spacing: 0.04em;
      font-size: var(--font-size-2xs);
    }
    .next-text {
      margin: 0;
      font-size: var(--font-size-sm);
      color: var(--text-muted);
      font-style: italic;
    }
    .empty {
      margin: 0;
      font-size: var(--font-size-body);
      color: var(--text-muted);
    }
  `,
})
export class StatusTimelineComponent {
  /** As returned by GET /api/v1/cases/{id}, in chronological order. */
  readonly history = input.required<StatusTransition[]>();
  readonly currentStatus = input.required<string>();

  protected readonly nextStep = computed(() =>
    isFinalStatus(this.currentStatus())
      ? ''
      : (ANALYST_NEXT_STEP[this.currentStatus() as CaseStatus] ?? ''),
  );
  protected readonly hasNextStep = computed(() => this.nextStep() !== '');
  protected readonly currentTone = computed<StatusTone>(() => caseStatusTone(this.currentStatus()));

  /** The backend records `from === to` for events that leave a trace without a status change (e.g. assignments). */
  protected isSameStatus(h: StatusTransition): boolean {
    return h.fromStatus !== null && h.fromStatus === h.toStatus;
  }

  protected statusLabel(status: string): string {
    return caseStatusLabel(status);
  }

  /** The backend reason embeds enum literals; this renders them as labels. */
  protected reasonNote(reason: string): string {
    return historyNote(reason);
  }

  protected actor(actor: StatusTransition['actor']): string {
    return ACTOR_LABELS[actor] ?? actor;
  }

  protected when(iso: string): string {
    return formatDateTime(iso);
  }
}
