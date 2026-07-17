import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { StatusTransition } from '../../../core/models/expediente';
import { estadoLabel, isEstadoFinal, proximoPaso } from '../../../core/models/estado';

const ACTOR_LABELS: Record<StatusTransition['actor'], string> = {
  SYSTEM: 'Sistema',
  INSURED: 'Asegurado',
  ANALYST: 'Analista',
};

/**
 * Timeline vertical del historial de estados de un expediente (trazabilidad del
 * Módulo de Expedientes). Cada hito viene del backend con timestamp, actor y motivo;
 * si el expediente no está resuelto, cierra con un hito "fantasma" que anticipa el
 * próximo paso esperado.
 */
@Component({
  selector: 'app-status-timeline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol class="timeline">
      @for (h of history(); track $index; let last = $last) {
        <li class="step" [class.current]="last && !hasNextStep()">
          <span class="marker" aria-hidden="true"></span>
          <div class="body">
            <div class="when mono">{{ when(h.changedAt) }}</div>
            <div class="transition">
              @if (h.fromStatus) {
                <span class="from">{{ estado(h.fromStatus) }}</span>
                <span class="arrow" aria-hidden="true">→</span>
              }
              <span class="badge">{{ estado(h.toStatus) }}</span>
            </div>
            <div class="meta">
              <span class="actor">{{ actor(h.actor) }}</span>
              <span class="reason">{{ h.reason }}</span>
            </div>
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
    :host { display: block; }
    .timeline { list-style: none; margin: 0; padding: 0; }
    .step { position: relative; padding: 0 0 18px 26px; }
    /* Línea que conecta los hitos */
    .step:not(:last-child)::before {
      content: '';
      position: absolute;
      left: 6px;
      top: 16px;
      bottom: -2px;
      width: 1px;
      background: var(--c-border-2);
    }
    .marker {
      position: absolute;
      left: 0;
      top: 3px;
      width: 13px;
      height: 13px;
      border-radius: 50%;
      background: var(--c-bg);
      border: 2px solid var(--c-border-strong);
    }
    .step.current .marker { background: var(--c-ink); border-color: var(--c-ink); }
    .step.next .marker { border-style: dashed; border-color: var(--c-muted-2); }
    .step.next::before { display: none; }

    .when { font-size: 11px; color: var(--c-muted); margin-bottom: 3px; }
    .transition { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; }
    .from { font-size: 12px; color: var(--c-muted); }
    .arrow { color: var(--c-muted-2); }
    .badge {
      display: inline-block;
      font-size: 11px;
      padding: 3px 10px;
      border-radius: var(--radius-pill);
      border: 1px solid var(--c-border-3);
      background: var(--c-bg-head);
      color: var(--c-ink-2);
    }
    .step.current .badge { border-color: var(--c-ink-3); color: var(--c-ink); font-weight: 600; }
    .meta { margin-top: 4px; display: flex; gap: 9px; align-items: baseline; flex-wrap: wrap; }
    .actor {
      font-size: 10px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: var(--c-ink-3);
    }
    .reason { font-size: 12px; color: var(--c-ink-2); }
    .step.next .when { text-transform: uppercase; letter-spacing: 0.04em; font-size: 10px; }
    .next-text { margin: 0; font-size: 12px; color: var(--c-muted); font-style: italic; }
    .empty { margin: 0; font-size: 13px; color: var(--c-muted); }
  `,
})
export class StatusTimelineComponent {
  /** Transiciones tal como las devuelve GET /api/v1/cases/{id} (orden cronológico). */
  readonly history = input.required<StatusTransition[]>();
  /** Estado actual del expediente; define el hito "próximo paso" cuando no es final. */
  readonly currentStatus = input.required<string>();

  protected readonly nextStep = computed(() =>
    isEstadoFinal(this.currentStatus()) ? '' : proximoPaso(this.currentStatus()),
  );
  protected readonly hasNextStep = computed(() => this.nextStep() !== '');

  protected estado(status: string): string {
    return estadoLabel(status);
  }

  protected actor(actor: StatusTransition['actor']): string {
    return ACTOR_LABELS[actor] ?? actor;
  }

  protected when(iso: string): string {
    return new Date(iso).toLocaleString('es-AR');
  }
}
