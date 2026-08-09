import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { StatusTransition } from '../../../core/models/expediente';
import { estadoLabel, estadoTone, isEstadoFinal, proximoPaso } from '../../../core/models/estado';
import { StatusTone } from '../../../core/models/status-tone';
import { formatDateTime } from '../../../core/util/datetime';
import { BadgeComponent } from '../badge/badge.component';

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
  imports: [BadgeComponent],
  template: `
    <ol class="timeline">
      @for (h of history(); track $index; let last = $last) {
        <li
          class="step"
          [class.current]="last && !hasNextStep()"
          [attr.data-tone]="last && !hasNextStep() && currentTone() !== 'neutral' ? currentTone() : null"
        >
          <span class="marker" aria-hidden="true"></span>
          <div class="body">
            <div class="when mono">{{ when(h.changedAt) }}</div>
            <div class="transition">
              @if (isSameStatus(h)) {
                <!-- Hito que no movió el expediente de estado (ej. una asignación): mostrar
                     "X → X" sería ruido, alcanza con el motivo que va abajo. -->
                <span class="from">Sin cambio de estado</span>
              } @else {
                @if (h.fromStatus) {
                  <span class="from">{{ estado(h.fromStatus) }}</span>
                  <span class="arrow" aria-hidden="true">→</span>
                }
                @if (last && !hasNextStep()) {
                  <app-badge variant="strong" [tone]="currentTone()">{{ estado(h.toStatus) }}</app-badge>
                } @else {
                  <app-badge>{{ estado(h.toStatus) }}</app-badge>
                }
              }
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
    .step { position: relative; padding: 0 0 var(--space-4) var(--space-5); }
    /* Línea que conecta los hitos */
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
    .step.current .marker { background: var(--accent); border-color: var(--accent); }
    /* El hito actual toma el color de semáforo del estado (resolución/curso). */
    .step.current[data-tone='ok'] .marker { background: var(--status-ok); border-color: var(--status-ok); }
    .step.current[data-tone='warning'] .marker { background: var(--status-warning); border-color: var(--status-warning); }
    .step.current[data-tone='danger'] .marker { background: var(--status-danger); border-color: var(--status-danger); }
    .step.current[data-tone='info'] .marker { background: var(--status-info); border-color: var(--status-info); }
    .step.next .marker { border-style: dashed; border-color: var(--text-muted); }
    .step.next::before { display: none; }

    .when { font-size: var(--font-size-xs); color: var(--text-muted); margin-bottom: 3px; }
    .transition { display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; }
    .from { font-size: var(--font-size-sm); color: var(--text-muted); }
    .arrow { color: var(--text-muted); }
    .meta { margin-top: var(--space-1); display: flex; gap: var(--space-2); align-items: baseline; flex-wrap: wrap; }
    .actor {
      font-size: var(--font-size-2xs);
      font-weight: var(--font-weight-medium);
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: var(--text-tertiary);
    }
    .reason { font-size: var(--font-size-sm); color: var(--text-secondary); }
    .step.next .when { text-transform: uppercase; letter-spacing: 0.04em; font-size: var(--font-size-2xs); }
    .next-text { margin: 0; font-size: var(--font-size-sm); color: var(--text-muted); font-style: italic; }
    .empty { margin: 0; font-size: var(--font-size-body); color: var(--text-muted); }
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
  protected readonly currentTone = computed<StatusTone>(() => estadoTone(this.currentStatus()));

  /**
   * Hito registrado sin cambio de estado. El backend usa `from === to` para eventos que dejan
   * traza pero no mueven el expediente — hoy, las asignaciones de analista.
   */
  protected isSameStatus(h: StatusTransition): boolean {
    return h.fromStatus !== null && h.fromStatus === h.toStatus;
  }

  protected estado(status: string): string {
    return estadoLabel(status);
  }

  protected actor(actor: StatusTransition['actor']): string {
    return ACTOR_LABELS[actor] ?? actor;
  }

  protected when(iso: string): string {
    return formatDateTime(iso);
  }
}
