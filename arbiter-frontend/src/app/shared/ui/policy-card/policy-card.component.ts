import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import {
  Policy,
  policyPaymentLabel,
  policyPaymentTone,
  policyValidity,
  policyValidityLabel,
  policyValidityTone,
} from '../../../core/models/policy';
import { formatDate } from '../../../core/util/datetime';
import { formatDeductible, formatMoney } from '../../../core/util/money';
import { BadgeComponent } from '../badge/badge.component';

/**
 * Una póliza del asegurado, en solo lectura. La usan la bienvenida (H0009) y "Mis pólizas" del
 * perfil: es el mismo dato y tiene que leerse igual en los dos lados.
 *
 * Las coberturas se despliegan en vez de mostrarse siempre: son el dato que el asegurado más
 * pregunta —cuánto le cubre y cuánta franquicia tiene—, pero con tres pólizas abiertas a la vez
 * la pantalla se vuelve una tabla y se pierde justo lo que vino a buscar. `compact` las saca del
 * todo, para la bienvenida, donde las pólizas son contexto de un formulario y no el tema.
 *
 * Nada de esto se edita desde Arbiter: la póliza es dato de la compañía (decisión #10).
 */
@Component({
  selector: 'app-policy-card',
  imports: [BadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article class="policy" [attr.data-validity]="validity()">
      <div class="policy-head">
        <span class="policy-insurer">{{ policy().insurerName }}</span>
        <span class="policy-number mono">{{ policy().policyNumber }}</span>
      </div>

      <h3 class="policy-item">{{ policy().insuredItem ?? policy().product }}</h3>

      <div class="policy-status">
        <app-badge [tone]="validityTone()">{{ validityLabel() }}</app-badge>
        <app-badge [tone]="paymentTone()">{{ paymentLabel() }}</app-badge>
      </div>

      <dl class="policy-meta">
        <div>
          <dt>Ramo</dt>
          <dd>{{ policy().branch }}</dd>
        </div>
        <div>
          <dt>Producto</dt>
          <dd>{{ policy().product }}</dd>
        </div>
        @if (policy().insuredItem) {
          <div>
            <dt>Bien asegurado</dt>
            <dd>{{ policy().insuredItem }}</dd>
          </div>
        }
        <div>
          <dt>Vigencia</dt>
          <dd class="tabular">{{ vigencia() }}</dd>
        </div>
      </dl>

      @if (!compact()) {
        @if (coverages().length > 0) {
          <button
            type="button"
            class="coverages-toggle"
            [attr.aria-expanded]="open()"
            [attr.aria-controls]="panelId()"
            (click)="toggle()"
          >
            <span class="chev" [class.is-open]="open()" aria-hidden="true">›</span>
            {{ open() ? 'Ocultar coberturas' : 'Ver coberturas' }}
            <span class="count">({{ coverages().length }})</span>
          </button>

          @if (open()) {
            <ul class="coverages" [id]="panelId()">
              @for (c of coverages(); track c.code) {
                <li class="coverage">
                  <span class="coverage-name">{{ c.description }}</span>
                  <span class="coverage-figures">
                    <span class="figure">
                      <span class="figure-label">Suma asegurada</span>
                      <span class="figure-value tabular">{{ money(c.insuredAmount) }}</span>
                    </span>
                    <span class="figure">
                      <span class="figure-label">Franquicia</span>
                      <span class="figure-value tabular">{{
                        deductible(c.deductible, c.deductiblePct)
                      }}</span>
                    </span>
                  </span>
                </li>
              }
            </ul>
          }
        } @else {
          <p class="no-coverages t-note">
            Tu aseguradora no informó el detalle de coberturas de esta póliza.
          </p>
        }
      }
    </article>
  `,
  styles: `
    :host {
      display: block;
    }

    .policy {
      padding: var(--space-3) var(--space-4);
      /* Filo teal a la izquierda: marca la póliza como "tuyo" sin teñir toda la tarjeta. */
      border: 1px solid var(--border-default);
      border-left: 3px solid var(--accent);
      border-radius: var(--radius-card);
      background: var(--surface-soft);
    }

    /* Vencida: el filo pierde el teal y la tarjeta se apaga. Es lo que la distingue de un vistazo
       sin leer las fechas — el badge solo confirma lo que el bloque entero ya dice. */
    .policy[data-validity='vencida'] {
      border-left-color: var(--border-strong);
      background: var(--surface-sunken);
    }
    .policy[data-validity='vencida'] .policy-insurer,
    .policy[data-validity='vencida'] .policy-item {
      color: var(--text-tertiary);
    }

    .policy-head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--space-3);
    }
    .policy-insurer {
      font-size: var(--font-size-2xs);
      font-weight: var(--font-weight-medium);
      text-transform: uppercase;
      letter-spacing: 0.1em;
      color: var(--accent-fg);
    }
    .policy-number {
      font-size: var(--font-size-2xs);
      color: var(--text-tertiary);
    }

    .policy-item {
      margin: var(--space-1) 0 0;
      font-size: var(--font-size-md);
      font-weight: var(--font-weight-medium);
      color: var(--text-primary);
    }

    .policy-status {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
      margin-top: var(--space-2);
    }

    .policy-meta {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2) var(--space-5);
      margin: var(--space-3) 0 0;
    }
    .policy-meta dt {
      font-size: var(--font-size-2xs);
      color: var(--text-muted);
    }
    .policy-meta dd {
      margin: 0;
      font-size: var(--font-size-body);
      color: var(--text-secondary);
    }

    /* ───────────────── Coberturas ───────────────── */
    .coverages-toggle {
      display: inline-flex;
      align-items: center;
      gap: var(--space-2);
      margin-top: var(--space-3);
      padding: 0;
      border: 0;
      background: none;
      font: inherit;
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-medium);
      color: var(--accent-fg);
      cursor: pointer;
    }
    .coverages-toggle:hover {
      text-decoration: underline;
    }
    .coverages-toggle:focus-visible {
      outline: 2px solid var(--border-focus);
      outline-offset: 2px;
      border-radius: var(--radius-ctl);
    }
    .count {
      color: var(--text-muted);
      font-weight: var(--font-weight-regular);
    }
    .chev {
      display: inline-block;
      transition: transform var(--dur-1) ease;
    }
    .chev.is-open {
      transform: rotate(90deg);
    }

    .coverages {
      list-style: none;
      margin: var(--space-3) 0 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
    }
    .coverage {
      padding: var(--space-2) var(--space-3);
      border: 1px solid var(--border-subtle);
      border-radius: var(--radius-ctl);
      background: var(--surface);
    }
    .coverage-name {
      display: block;
      font-size: var(--font-size-body);
      font-weight: var(--font-weight-medium);
      color: var(--text-primary);
    }
    .coverage-figures {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-1) var(--space-5);
      margin-top: var(--space-1);
    }
    .figure {
      display: flex;
      flex-direction: column;
    }
    .figure-label {
      font-size: var(--font-size-2xs);
      color: var(--text-muted);
    }
    .figure-value {
      font-size: var(--font-size-body);
      color: var(--text-secondary);
    }

    .no-coverages {
      margin: var(--space-3) 0 0;
    }
  `,
})
export class PolicyCardComponent {
  readonly policy = input.required<Policy>();

  /** Sin coberturas desplegables: la póliza es contexto, no el tema de la pantalla. */
  readonly compact = input(false);

  protected readonly open = signal(false);

  protected readonly validity = computed(() => policyValidity(this.policy()));
  protected readonly validityLabel = computed(() => policyValidityLabel(this.policy()));
  protected readonly validityTone = computed(() => policyValidityTone(this.policy()));
  protected readonly paymentLabel = computed(() => policyPaymentLabel(this.policy()));
  protected readonly paymentTone = computed(() => policyPaymentTone(this.policy()));

  protected readonly coverages = computed(() => this.policy().coverages ?? []);

  protected readonly vigencia = computed(
    () => `${formatDate(this.policy().effectiveFrom)} – ${formatDate(this.policy().effectiveTo)}`,
  );

  /** El id sale del número de póliza: con varias tarjetas, `aria-controls` tiene que ser único. */
  protected readonly panelId = computed(
    () => `coverages-${this.policy().policyNumber.replace(/[^\w-]/g, '')}`,
  );

  protected readonly money = formatMoney;
  protected readonly deductible = formatDeductible;

  protected toggle(): void {
    this.open.update((v) => !v);
  }
}
