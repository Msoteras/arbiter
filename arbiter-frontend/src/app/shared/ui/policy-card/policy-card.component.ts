import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import {
  Policy,
  policyPaymentLabel,
  policyPaymentTone,
  policyValidityLabel,
  policyValidityTone,
} from '../../../core/models/policy';
import { formatDate } from '../../../core/util/datetime';
import { formatDeductible, formatMoney } from '../../../core/util/money';
import { BadgeComponent } from '../badge/badge.component';

/**
 * Read-only: policies are insurer data and are never edited in Arbiter. Coverages are collapsed
 * by default so several open policies do not turn the screen into a table.
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
      border: 1px solid var(--border-default);
      border-left: 3px solid var(--accent);
      border-radius: var(--radius-card);
      background: var(--surface-soft);
    }

    .policy[data-validity='EXPIRED'] {
      border-left-color: var(--border-strong);
      background: var(--surface-sunken);
    }
    .policy[data-validity='EXPIRED'] .policy-insurer,
    .policy[data-validity='EXPIRED'] .policy-item {
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

  /** Hides coverages, for screens where the policy is context rather than the subject. */
  readonly compact = input(false);

  protected readonly open = signal(false);

  protected readonly validity = computed(() => this.policy().validity);
  protected readonly validityLabel = computed(() => policyValidityLabel(this.policy()));
  protected readonly validityTone = computed(() => policyValidityTone(this.policy()));
  protected readonly paymentLabel = computed(() => policyPaymentLabel(this.policy()));
  protected readonly paymentTone = computed(() => policyPaymentTone(this.policy()));

  protected readonly coverages = computed(() => this.policy().coverages ?? []);

  protected readonly vigencia = computed(
    () => `${formatDate(this.policy().effectiveFrom)} – ${formatDate(this.policy().effectiveTo)}`,
  );

  protected readonly panelId = computed(
    () => `coverages-${this.policy().policyNumber.replace(/[^\w-]/g, '')}`,
  );

  protected readonly money = formatMoney;
  protected readonly deductible = formatDeductible;

  protected toggle(): void {
    this.open.update((v) => !v);
  }
}
