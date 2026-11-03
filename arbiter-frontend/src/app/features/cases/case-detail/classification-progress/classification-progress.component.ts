import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';

import { formatTime } from '../../../../core/util/datetime';
import { InfoTipComponent } from '../../../../shared/ui/info-tip/info-tip.component';

const TICK_MS = 30_000;

/**
 * Where a case stands while it is being classified. The backend doesn't report the stage it is in,
 * so the middle step is one: "en curso" plus how long it has been. Claiming a sub-stage would be a
 * guess.
 */
@Component({
  selector: 'app-classification-progress',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [InfoTipComponent],
  template: `
    <ol class="steps">
      <li class="step done">
        <span class="mark" aria-hidden="true">✓</span>
        <span class="label">Denuncia recibida</span>
        <span class="meta">{{ receivedTime() }}</span>
      </li>
      <li class="step current" aria-current="step">
        <span class="mark" aria-hidden="true"></span>
        <span class="label">
          Análisis en curso
          <app-info-tip>
            Arbiter evalúa las reglas de la aseguradora, lee los documentos y compara las imágenes.
          </app-info-tip>
        </span>
        <span class="meta" [class.late]="late()">{{ elapsed() }}</span>
      </li>
      <li class="step">
        <span class="mark" aria-hidden="true"></span>
        <span class="label">Recomendación lista</span>
      </li>
    </ol>
  `,
  styles: `
    :host {
      display: block;
    }
    .steps {
      list-style: none;
      margin: 0;
      padding: 0;
      display: grid;
      gap: var(--space-2);
    }
    .step {
      position: relative;
      display: grid;
      grid-template-columns: var(--icon-md) 1fr auto;
      align-items: center;
      gap: var(--space-2);
      font-size: var(--font-size-sm);
      color: var(--text-muted);
    }
    /* Connector between marks. */
    .step + .step::before {
      content: '';
      position: absolute;
      left: calc(var(--icon-md) / 2);
      bottom: 100%;
      height: var(--space-2);
      border-left: 1px solid var(--border-default);
    }
    .mark {
      display: grid;
      place-items: center;
      width: var(--icon-md);
      height: var(--icon-md);
      border-radius: var(--radius-pill);
      border: 1px solid var(--border-control);
      background: var(--surface);
      font-size: var(--font-size-2xs);
    }
    .step.done {
      color: var(--text-secondary);
    }
    .step.done .mark {
      border-color: var(--accent-fill-border);
      background: var(--accent-fill);
      color: var(--text-on-emphasis);
    }
    .step.current {
      color: var(--text-primary);
      font-weight: var(--font-weight-medium);
    }
    .step.current .mark {
      border-color: var(--border-focus);
      box-shadow: var(--focus-ring);
      animation: mark-breathe 1.8s var(--ease-standard) infinite;
    }
    .label {
      display: inline-flex;
      align-items: center;
      gap: var(--space-1);
    }
    .meta {
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-regular);
      color: var(--text-muted);
    }
    .meta.late {
      color: var(--text-secondary);
    }
    @keyframes mark-breathe {
      50% {
        box-shadow: 0 0 0 0 transparent;
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .step.current .mark {
        animation: none;
      }
    }
  `,
})
export class ClassificationProgressComponent {
  /** When the case entered classification (the claim, or the latest requeue). */
  readonly since = input.required<string>();

  private readonly now = signal(Date.now());

  constructor() {
    const timer = setInterval(() => this.now.set(Date.now()), TICK_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected readonly receivedTime = computed(() => formatTime(this.since()));

  private readonly minutes = computed(() =>
    Math.max(0, Math.floor((this.now() - new Date(this.since()).getTime()) / 60_000)),
  );

  protected readonly elapsed = computed(() => {
    const m = this.minutes();
    if (m < 1) return 'recién';
    if (m < 60) return `hace ${m} min`;
    const h = Math.floor(m / 60);
    return m % 60 ? `hace ${h} h ${m % 60} min` : `hace ${h} h`;
  });

  /** Past the 30 minutes the platform commits to. */
  protected readonly late = computed(() => this.minutes() >= 30);
}
