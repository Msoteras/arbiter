import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

type Severity = 'low' | 'medium' | 'high';

/** Severity is conveyed by font weight and a triangle glyph, never by color (design rule). */
@Component({
  selector: 'app-severity-label',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="sev" [class.medium]="level() === 'medium'" [class.high]="level() === 'high'">
      @if (level() !== 'low') {
        <span class="tri" aria-hidden="true">▲</span>
      }
      {{ text() }}
    </span>
  `,
  styles: `
    .sev {
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      display: inline-flex;
      gap: 3px;
      align-items: center;
    }
    .sev.medium {
      color: var(--text-tertiary);
      font-weight: var(--font-weight-medium);
    }
    .sev.high {
      color: var(--text-primary);
      font-weight: var(--font-weight-bold);
    }
    .tri {
      font-size: var(--font-size-2xs);
    }
  `,
})
export class SeverityLabelComponent {
  readonly level = input.required<Severity>();
  protected readonly text = computed(
    () => ({ low: 'Bajo', medium: 'Medio', high: 'Alto' })[this.level()],
  );
}
