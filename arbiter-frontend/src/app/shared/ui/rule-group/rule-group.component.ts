import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * A set of `app-rule-block` that share one effect. The heading says that effect ("Es Fast Track si
 * cumple todas las condiciones", "Cuando el asegurado hace la denuncia"), so each block only says
 * its own condition. The blocks go in the default slot, and `[groupFooter]` under them (an "add"
 * action, an empty-list warning).
 */
@Component({
  selector: 'app-rule-group',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="group" [attr.aria-labelledby]="headingId">
      <h3 class="heading" [id]="headingId">{{ heading() }}</h3>
      <div class="blocks"><ng-content /></div>
      <ng-content select="[groupFooter]" />
    </section>
  `,
  styles: `
    :host {
      display: block;
    }
    /* A sentence, not a label: sentence case, the size of the body text. */
    .heading {
      margin: 0 0 var(--space-3);
      font-size: var(--font-size-body);
      font-weight: var(--font-weight-medium);
      color: var(--text-primary);
    }
    .blocks {
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
    }
    .blocks:empty {
      display: none;
    }
    :host ::ng-deep [groupFooter] {
      margin-top: var(--space-3);
    }
  `,
})
export class RuleGroupComponent {
  private static nextId = 0;

  readonly heading = input.required<string>();
  protected readonly headingId = `rule-group-${RuleGroupComponent.nextId++}`;
}
