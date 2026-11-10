import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  ViewEncapsulation,
} from '@angular/core';

import { StatusTone } from '../../../core/models/status-tone';
import { AsciiFieldComponent } from '../ascii-field/ascii-field.component';

/**
 * A strip about one thing: a headline, the one figure it is about and the actions it calls for.
 * Slots, all optional but the title:
 * - `spotlightLabel`: the line above the title (the Arbiter logo, an insurer, a date).
 * - `spotlightTitle`: the headline, as a real heading. A `.spotlight-tone` span inside it takes the tone.
 * - `spotlightFigure`: the figure, laid out on the title's baseline. Use `.spotlight-number`
 *   (`.is-money`, `.is-empty`) and `.spotlight-unit` for the words beside it.
 * - `spotlightSummary`: one line under both.
 * - `spotlightAside`: a short summary beside the hero, as tall as it (`.spotlight-aside-section`,
 *   `.spotlight-rows.is-compact`). On a wide strip it sits between the figure and the actions; on a
 *   narrower one, under the figure.
 * - `spotlightActions`: the column on the right; without it the strip takes the whole width.
 * - default content: what goes below, inside the frame (`.spotlight-panels`, `.spotlight-cells`).
 *
 * The tone colors the headline word, the figure's label and the glyph field right of the figure.
 * Encapsulation is off so those helper classes reach the projected content; every rule is scoped
 * to `app-spotlight`.
 */
@Component({
  selector: 'app-spotlight',
  imports: [AsciiFieldComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  host: { '[style.--spotlight-tone]': 'toneColor()' },
  template: `
    <div class="sl-hero">
      <div class="sl-main">
        <div class="sl-grid">
          @if (field()) {
            <app-ascii-field [tone]="tone()" />
          }
          <div class="sl-label"><ng-content select="[spotlightLabel]" /></div>
          <div class="sl-title"><ng-content select="[spotlightTitle]" /></div>
          <div class="sl-figure"><ng-content select="[spotlightFigure]" /></div>
          <div class="sl-summary"><ng-content select="[spotlightSummary]" /></div>
        </div>
      </div>
      <div class="sl-aside"><ng-content select="[spotlightAside]" /></div>
      <div class="sl-actions"><ng-content select="[spotlightActions]" /></div>
    </div>
    <ng-content />
  `,
  styles: `
    @use 'media';

    @mixin stacked {
      grid-template-columns: minmax(0, 1fr);
      grid-template-areas: 'label' 'title' 'figure' 'summary';
      row-gap: var(--space-4);
      min-height: 0;
      padding: var(--space-5);

      & > .sl-label {
        margin-bottom: 0;
      }
      /* No free column left: the field goes behind the whole hero, clear of the text side. */
      & > app-ascii-field {
        grid-column: 1;
        inset: calc(-1 * var(--space-5));
        --ascii-field-fade: 0.5;
        opacity: 0.6;
      }
    }

    app-spotlight {
      container: spotlight / inline-size;
      display: grid;
      gap: var(--spotlight-gap);
      padding: var(--spotlight-gap);
      border-radius: var(--radius-frame);
      background: var(--surface-sunken);
      border: 1px solid var(--border-default);
      color: var(--text-primary);
    }
    app-spotlight .sl-hero,
    app-spotlight .spotlight-panel,
    app-spotlight .spotlight-cell {
      position: relative;
      min-width: 0;
      border-radius: var(--radius-panel);
      background: var(--surface);
      border: 1px solid var(--border-subtle);
      box-shadow: var(--shadow-card);
    }
    app-spotlight .sl-hero {
      display: grid;
      grid-template-columns: minmax(0, 1fr) var(--spotlight-actions-w);
      grid-template-areas: 'main actions';
      overflow: hidden;
    }
    app-spotlight .sl-hero:has(> .sl-actions:empty) {
      grid-template-columns: minmax(0, 1fr);
      grid-template-areas: 'main';
    }
    app-spotlight .sl-hero:has(> .sl-aside:not(:empty)) {
      grid-template-areas: 'main actions' 'aside actions';
    }
    app-spotlight .sl-hero:has(> .sl-aside:not(:empty)):has(> .sl-actions:empty) {
      grid-template-areas: 'main' 'aside';
    }
    app-spotlight .sl-main {
      grid-area: main;
      position: relative;
      min-width: 0;
      overflow: hidden;
    }
    /* The glyph field fills the free column right of the figure, out to the hero's edges. */
    app-spotlight .sl-grid > app-ascii-field {
      grid-column: 3;
      grid-row: 1 / -1;
      inset: calc(-1 * var(--space-5)) calc(-1 * var(--space-6)) calc(-1 * var(--space-5)) 0;
    }
    app-spotlight .sl-grid {
      position: relative;
      display: grid;
      grid-template-columns: minmax(min-content, max-content) auto minmax(40px, 1fr);
      grid-template-areas: 'label label .' 'title figure .' 'summary summary .';
      column-gap: var(--space-7);
      align-items: last baseline;
      align-content: end;
      min-height: var(--spotlight-min-h);
      padding: var(--space-5) var(--space-6) var(--space-5);
    }
    app-spotlight .sl-grid:has(> .sl-figure:empty) {
      grid-template-areas: 'label label .' 'title title .' 'summary summary .';
    }
    app-spotlight .sl-label {
      grid-area: label;
      align-self: start;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2) var(--space-3);
      margin-bottom: var(--space-4);
      font-size: var(--font-size-body);
      color: var(--text-muted);
    }
    /* Each slot wraps what it projects: the layout goes on that projected element. */
    app-spotlight .sl-label > * {
      display: inline-flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2);
    }
    app-spotlight .sl-figure > * {
      display: flex;
      align-items: flex-end;
      gap: var(--space-3);
    }
    app-spotlight .sl-summary > * {
      margin: 0;
    }
    app-spotlight .sl-actions > * {
      display: grid;
      gap: var(--space-2);
    }
    app-spotlight .sl-label app-logo {
      --logo-size: var(--font-size-lg);
      color: var(--text-primary);
    }
    app-spotlight .sl-title {
      grid-area: title;
    }
    app-spotlight .sl-title > * {
      margin: 0;
      font-size: var(--font-size-headline);
      font-weight: var(--font-weight-regular);
      letter-spacing: -0.035em;
      line-height: 1.04;
      text-wrap: balance;
    }
    app-spotlight .spotlight-tone {
      color: var(--spotlight-tone);
    }
    app-spotlight .sl-figure {
      grid-area: figure;
      display: flex;
      align-items: flex-end;
      gap: var(--space-3);
    }
    app-spotlight .sl-figure:empty {
      display: none;
    }
    app-spotlight .spotlight-number {
      font-size: var(--font-size-figure);
      font-weight: var(--font-weight-regular);
      letter-spacing: -0.06em;
      line-height: 0.82;
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }
    app-spotlight .spotlight-number.is-money {
      font-size: var(--font-size-figure-money);
      letter-spacing: -0.05em;
    }
    app-spotlight .spotlight-number.is-empty {
      color: var(--text-muted);
    }
    app-spotlight .spotlight-unit {
      display: grid;
      gap: var(--space-1);
      padding-bottom: var(--space-1);
      font-size: var(--font-size-md);
      color: var(--text-muted);
      white-space: nowrap;
    }
    app-spotlight .spotlight-unit b {
      font-weight: var(--font-weight-regular);
      color: var(--spotlight-tone);
    }
    app-spotlight .sl-summary {
      grid-area: summary;
      margin-top: var(--space-3);
      font-size: var(--font-size-md);
      color: var(--text-muted);
    }
    app-spotlight .sl-summary:empty {
      display: none;
    }
    app-spotlight .sl-summary strong {
      font-weight: var(--font-weight-medium);
      color: var(--text-primary);
    }
    app-spotlight .sl-aside {
      grid-area: aside;
      display: grid;
      align-content: center;
      min-width: 0;
      padding: var(--space-5);
      border-top: 1px solid var(--border-subtle);
    }
    app-spotlight .sl-aside > * {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(var(--spotlight-aside-min-w), 1fr));
      gap: var(--space-4) var(--space-6);
    }
    app-spotlight .sl-aside:empty {
      display: none;
    }
    app-spotlight .spotlight-aside-section {
      display: grid;
      align-content: start;
      gap: var(--space-2);
      min-width: 0;
    }
    app-spotlight .spotlight-aside-head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--space-3);
    }
    app-spotlight .spotlight-rows.is-compact {
      gap: 0;
    }
    app-spotlight .spotlight-rows.is-compact .spotlight-row {
      grid-template-columns: var(--space-3) minmax(0, 1fr) auto;
      gap: var(--space-2);
      padding: var(--space-1) 0;
      font-size: var(--font-size-body);
    }
    app-spotlight .spotlight-rows.is-compact .row-text {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    app-spotlight .sl-actions {
      grid-area: actions;
      display: grid;
      align-content: center;
      gap: var(--space-2);
      padding: var(--space-5);
      border-left: 1px solid var(--border-subtle);
    }
    app-spotlight .sl-actions:empty {
      display: none;
    }

    /* Below the hero: panels with lists of figures, and a row of count cells. */
    app-spotlight .spotlight-panels {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(var(--spotlight-actions-w), 1fr));
      gap: var(--spotlight-gap);
    }
    app-spotlight .spotlight-panel {
      display: grid;
      align-content: start;
      gap: var(--space-3);
      padding: var(--space-5);
    }
    app-spotlight .spotlight-panel-label {
      margin: 0;
      font-size: var(--font-size-body);
      font-weight: var(--font-weight-regular);
      color: var(--text-muted);
    }
    app-spotlight .spotlight-rows {
      list-style: none;
      margin: 0;
      padding: 0;
      display: grid;
      gap: 2px;
    }
    app-spotlight .spotlight-row {
      display: grid;
      grid-template-columns: var(--space-4) minmax(0, 1fr) auto;
      gap: var(--space-1) var(--space-3);
      align-items: baseline;
      padding: var(--space-2) 0;
      font-size: var(--font-size-md);
    }
    app-spotlight .spotlight-row .row-icon {
      font-family: var(--font-mono);
      font-size: var(--font-size-body);
      color: var(--text-muted);
    }
    app-spotlight .spotlight-row .row-value {
      font-family: var(--font-mono);
      font-size: var(--font-size-body);
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }
    app-spotlight .spotlight-row .row-value.is-hot {
      color: var(--spotlight-tone);
    }
    app-spotlight .spotlight-row .row-detail {
      grid-column: 2 / 4;
      font-size: var(--font-size-sm);
      color: var(--text-muted);
    }
    app-spotlight .spotlight-row .row-bar {
      grid-column: 2 / 4;
      height: 2px;
      margin-top: var(--space-2);
      border-radius: var(--radius-pill);
      background: var(--border-subtle);
      overflow: hidden;
    }
    app-spotlight .spotlight-row .row-bar > span {
      display: block;
      height: 100%;
      background: var(--spotlight-tone);
    }
    app-spotlight .spotlight-row.is-muted {
      color: var(--text-muted);
    }
    app-spotlight .spotlight-row.is-muted .row-value,
    app-spotlight .spotlight-row.is-deduction .row-value {
      color: var(--text-muted);
    }
    app-spotlight .spotlight-row.is-total {
      margin-top: var(--space-2);
      padding-top: var(--space-3);
      border-top: 1px solid var(--border-default);
      font-weight: var(--font-weight-medium);
    }
    app-spotlight .spotlight-row.is-total .row-value {
      font-size: var(--font-size-md);
      color: var(--text-primary);
    }
    app-spotlight .spotlight-row button {
      padding: 0;
      border: 0;
      background: none;
      font: inherit;
      color: inherit;
      text-align: left;
      text-decoration: underline;
      text-decoration-color: var(--border-strong);
      text-underline-offset: 3px;
      cursor: pointer;
    }
    app-spotlight .spotlight-cells {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
      gap: var(--spotlight-gap);
    }
    app-spotlight .spotlight-cell {
      display: grid;
      align-content: start;
      gap: var(--space-1);
      padding: var(--space-4);
      color: inherit;
      text-decoration: none;
      transition: border-color 0.15s;
    }
    app-spotlight a.spotlight-cell:hover {
      border-color: var(--border-strong);
    }
    app-spotlight a.spotlight-cell:focus-visible {
      outline: 2px solid var(--border-focus);
      outline-offset: 2px;
    }
    app-spotlight .spotlight-cell .cell-value {
      font-size: var(--font-size-count);
      letter-spacing: -0.04em;
      line-height: 1;
      font-variant-numeric: tabular-nums;
    }
    app-spotlight .spotlight-cell.is-zero .cell-value {
      color: var(--text-muted);
    }
    app-spotlight .spotlight-cell .cell-label {
      font-size: var(--font-size-md);
      color: var(--text-muted);
    }
    app-spotlight .spotlight-cell .cell-flag {
      font-size: var(--font-size-sm);
      color: var(--spotlight-tone);
    }

    /* All breakpoints go by the strip's own width: it can sit in a modal or a narrow column. */
    /* Wide: the aside moves up between the figure and the actions, as tall as the hero. The hero
       never gets narrower than its title and figure: the title wraps first. */
    @container spotlight (min-width: 1080px) {
      app-spotlight .sl-hero:has(> .sl-aside:not(:empty)) {
        grid-template-columns:
          minmax(min-content, 1.2fr) minmax(var(--spotlight-aside-min-w), 1fr)
          var(--spotlight-actions-w);
        grid-template-areas: 'main aside actions';
      }
      app-spotlight .sl-hero:has(> .sl-aside:not(:empty)) .sl-aside {
        border-top: 0;
        border-left: 1px solid var(--border-subtle);
      }
      app-spotlight .sl-hero:has(> .sl-aside:not(:empty)) .sl-aside > * {
        grid-template-columns: minmax(0, 1fr);
      }
      app-spotlight .sl-hero:has(> .sl-aside:not(:empty)) .sl-grid {
        column-gap: var(--space-5);
      }
    }
    @container spotlight (max-width: 760px) {
      app-spotlight .sl-hero,
      app-spotlight .sl-hero:has(> .sl-aside:not(:empty)) {
        grid-template-columns: minmax(0, 1fr);
        grid-template-areas: 'main' 'aside' 'actions';
      }
      app-spotlight .sl-actions {
        border-left: 0;
        border-top: 1px solid var(--border-subtle);
      }
    }
    /* The figure goes under the title once the hero is under ~640px: in a modal or on a phone
       (the whole strip), or beside the actions column on a mid-width strip. */
    @container spotlight (max-width: 652px) {
      app-spotlight .sl-grid,
      app-spotlight .sl-grid:has(> .sl-figure:empty) {
        @include stacked;
      }
    }
    @container spotlight (min-width: 761px) and (max-width: 932px) {
      app-spotlight .sl-hero:not(:has(> .sl-actions:empty)) .sl-grid,
      app-spotlight .sl-hero:not(:has(> .sl-actions:empty)) .sl-grid:has(> .sl-figure:empty) {
        @include stacked;
      }
    }
    @include media.phone {
      app-spotlight app-ascii-field {
        opacity: 0.35;
      }
      app-spotlight .sl-title > * {
        font-size: var(--font-size-2xl);
      }
      app-spotlight .spotlight-number {
        font-size: var(--font-size-figure-sm);
      }
      app-spotlight .spotlight-number.is-money {
        font-size: var(--font-size-figure-money-sm);
      }
    }
  `,
})
export class SpotlightComponent {
  readonly tone = input<StatusTone>('neutral');
  /** The glyph field behind the figure; off for strips that repeat down a list. */
  readonly field = input(true);

  protected readonly toneColor = computed(() => {
    const tone = this.tone();
    return tone === 'neutral' ? 'var(--text-secondary)' : `var(--status-${tone}-text)`;
  });
}
