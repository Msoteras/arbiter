import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';

/** Same meaning as the dot of `app-tabs`: `required` is something missing, `pending` incomplete. */
export type RulesTreeStatus = 'required' | 'pending' | null;

export interface RulesTreeSection {
  id: string;
  label: string;
  status: RulesTreeStatus;
}

export interface RulesTreeCoverage {
  id: string;
  name: string;
  status: RulesTreeStatus;
}

export interface RulesTreeBranch {
  id: string;
  name: string;
  /** Null until the coverage summary answers: unknown is not zero. */
  count: number | null;
  status: RulesTreeStatus;
  expanded: boolean;
  /** Null while the branch's coverages load. */
  coverages: RulesTreeCoverage[] | null;
}

/** What the content panel shows; the tree only marks it. */
export type RulesTreeSelection =
  | { kind: 'section'; id: string }
  | { kind: 'branch'; branchId: string }
  | { kind: 'coverage'; branchId: string; coverageId: string }
  | { kind: 'none' };

const STATUS_LABELS: Record<NonNullable<RulesTreeStatus>, string> = {
  required: 'falta configurar',
  pending: 'incompleto',
};

/**
 * The three configuration levels of Reglas: the insurer's own sections, then each branch with its
 * coverages. Presentational: the rules screen owns the selection and the unsaved-changes guard.
 */
@Component({
  selector: 'app-rules-tree',
  imports: [NgTemplateOutlet, InlineLoadingComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="tree" aria-label="Niveles de configuración">
      <section class="group">
        <h2 class="t-section-label heading">Aseguradora</h2>
        <ul class="list">
          @for (s of sections(); track s.id) {
            <li>
              <button
                type="button"
                class="item"
                [class.active]="isSection(s.id)"
                [attr.aria-current]="isSection(s.id) ? 'page' : null"
                (click)="pickSection.emit(s.id)"
              >
                <span class="label">{{ s.label }}</span>
                <ng-container
                  *ngTemplateOutlet="dot; context: { $implicit: s.status, title: null }"
                />
              </button>
            </li>
          }
        </ul>
      </section>

      <section class="group">
        <h2 class="t-section-label heading">Ramos</h2>
        @if (loading()) {
          <app-inline-loading [size]="22" message="Cargando ramos…" />
        } @else {
          <ul class="list">
            @for (b of branches(); track b.id) {
              <li>
                <div class="row" [class.active]="isBranch(b.id)">
                  <button
                    type="button"
                    class="caret"
                    [class.open]="b.expanded"
                    [attr.aria-expanded]="b.expanded"
                    [attr.aria-label]="(b.expanded ? 'Contraer ' : 'Expandir ') + b.name"
                    (click)="toggleBranch.emit(b.id)"
                  >
                    <svg viewBox="0 0 12 12" aria-hidden="true">
                      <path
                        d="M4.5 2.5 8 6l-3.5 3.5"
                        fill="none"
                        stroke="currentColor"
                        stroke-width="1.6"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                      />
                    </svg>
                  </button>
                  <button
                    type="button"
                    class="item"
                    [attr.aria-current]="isBranch(b.id) ? 'page' : null"
                    (click)="pickBranch.emit(b.id)"
                  >
                    <span class="label">{{ b.name }}</span>
                    <span class="marks">
                      <ng-container
                        *ngTemplateOutlet="
                          dot;
                          context: {
                            $implicit: b.status,
                            title: b.count === 0 ? 'Sin coberturas configuradas' : null,
                          }
                        "
                      />
                      <span class="count tabular">{{ b.count ?? '' }}</span>
                    </span>
                  </button>
                </div>
                @if (b.expanded) {
                  <ul class="children">
                    @if (b.coverages === null) {
                      <li class="note t-note">Cargando coberturas…</li>
                    }
                    @for (c of b.coverages ?? []; track c.id) {
                      <li>
                        <button
                          type="button"
                          class="item"
                          [class.active]="isCoverage(b.id, c.id)"
                          [attr.aria-current]="isCoverage(b.id, c.id) ? 'page' : null"
                          (click)="pickCoverage.emit({ branchId: b.id, coverageId: c.id })"
                        >
                          <span class="label">{{ c.name || 'Cobertura sin nombre' }}</span>
                          <ng-container
                            *ngTemplateOutlet="dot; context: { $implicit: c.status, title: null }"
                          />
                        </button>
                      </li>
                    }
                    @if (canAdd()) {
                      <li>
                        <button type="button" class="item add" (click)="addCoverage.emit(b.id)">
                          + Agregar cobertura
                        </button>
                      </li>
                    }
                  </ul>
                }
              </li>
            } @empty {
              <li class="note t-note">No hay ramos configurados.</li>
            }
          </ul>
        }
      </section>

      <!-- Apart from the levels: the history configures nothing. -->
      <section class="group">
        <h2 class="t-section-label heading">Auditoría</h2>
        <ul class="list">
          <li>
            <button
              type="button"
              class="item history"
              [class.active]="historyActive()"
              [attr.aria-current]="historyActive() ? 'page' : null"
              (click)="pickHistory.emit()"
            >
              <svg
                class="history-icon"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <circle cx="12" cy="12" r="10" />
                <path d="M12 6v6l4 2" />
              </svg>
              <span class="label">Historial de cambios</span>
            </button>
          </li>
        </ul>
      </section>
    </nav>

    <ng-template #dot let-status let-title="title">
      @if (status) {
        <span
          class="dot"
          [class.is-required]="status === 'required'"
          [attr.title]="title ?? capitalized(statusLabel(status))"
          aria-hidden="true"
        ></span>
        <span class="sr-only">({{ title ?? statusLabel(status) }})</span>
      } @else {
        <span class="dot-slot" aria-hidden="true"></span>
      }
    </ng-template>
  `,
  styles: `
    @use 'media';

    :host {
      display: block;
    }
    .tree {
      display: flex;
      flex-direction: column;
      gap: var(--space-5);
    }
    .group {
      display: flex;
      flex-direction: column;
    }
    .heading {
      margin: 0;
      padding: 0 var(--space-2) var(--space-2);
    }
    .list,
    .children {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: var(--space-0-5);
    }
    .children {
      margin: var(--space-0-5) 0 0 var(--tree-indent);
      padding-left: var(--space-2);
      border-left: 1px solid var(--border-default);
    }
    /* The open branch is one highlighted row, chevron included. */
    .row {
      display: flex;
      align-items: center;
      border-radius: var(--radius-ctl);
    }
    .row:hover {
      background: var(--surface-sunken);
    }
    .row.active {
      background: var(--selected-bg);
      color: var(--accent-fg);
    }
    .row .item:hover {
      background: none;
    }
    .item {
      flex: 1 1 auto;
      min-width: 0;
      width: 100%;
      display: flex;
      align-items: center;
      gap: var(--space-2);
      padding: var(--tree-item-pad-y) var(--space-2);
      border: 0;
      border-radius: var(--radius-ctl);
      background: none;
      font: inherit;
      font-size: var(--font-size-body);
      color: var(--text-primary);
      text-align: left;
      cursor: pointer;
    }
    .item:hover {
      background: var(--surface-sunken);
    }
    .item:focus-visible,
    .caret:focus-visible {
      outline: none;
      box-shadow: var(--focus-ring);
    }
    .item.active,
    .row.active .item {
      color: var(--accent-fg);
      font-weight: var(--font-weight-medium);
    }
    .item.active {
      background: var(--selected-bg);
    }
    .item.add {
      color: var(--accent-fg);
    }
    .row .item {
      padding-left: var(--space-1);
    }
    .label {
      flex: 1 1 auto;
      min-width: 0;
      overflow-wrap: anywhere;
    }
    /* Dot and counter in fixed slots, so the counters line up with or without a dot. */
    .marks {
      flex: none;
      display: inline-flex;
      align-items: center;
      gap: var(--space-2);
    }
    .count {
      width: var(--tree-count-w);
      text-align: right;
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-regular);
      color: var(--text-tertiary);
    }
    .row.active .count {
      color: var(--accent-fg);
    }
    .caret {
      flex: 0 0 auto;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: var(--space-5);
      align-self: stretch;
      padding: 0;
      border: 0;
      border-radius: var(--radius-ctl);
      background: none;
      color: inherit;
      cursor: pointer;
    }
    .caret svg {
      width: var(--icon-sm);
      height: var(--icon-sm);
      transition: transform var(--dur-2) var(--ease-out);
    }
    .caret.open svg {
      transform: rotate(90deg);
    }
    @media (prefers-reduced-motion: reduce) {
      .caret svg {
        transition: none;
      }
    }
    @include media.touch {
      .item,
      .caret {
        min-height: var(--touch-target);
      }
    }
    .note {
      padding: var(--space-1) var(--space-2);
    }
    .history-icon {
      flex: none;
      width: var(--icon-md);
      height: var(--icon-md);
      color: var(--text-tertiary);
    }
    .item.history.active .history-icon {
      color: var(--accent-fg);
    }
    /* The dot of app-tabs: neutral when incomplete, warning when something is missing. */
    .dot,
    .dot-slot {
      flex: 0 0 auto;
      display: inline-block;
      width: var(--space-2);
      height: var(--space-2);
      border-radius: var(--radius-pill);
    }
    .dot {
      background: var(--text-tertiary);
    }
    .dot.is-required {
      background: var(--status-warning);
    }
    /* Outside a branch row the empty slot would only push the label. */
    .children .dot-slot,
    .list > li > .item .dot-slot {
      display: none;
    }
  `,
})
export class RulesTreeComponent {
  readonly sections = input<readonly RulesTreeSection[]>([]);
  readonly branches = input<readonly RulesTreeBranch[]>([]);
  readonly selection = input<RulesTreeSelection>({ kind: 'none' });
  readonly loading = input(false);
  /** Shows "+ Agregar cobertura" under each open branch. */
  readonly canAdd = input(false);
  /** Marks "Historial de cambios" as the open view. */
  readonly historyActive = input(false);

  readonly pickSection = output<string>();
  readonly pickBranch = output<string>();
  readonly toggleBranch = output<string>();
  readonly pickCoverage = output<{ branchId: string; coverageId: string }>();
  readonly addCoverage = output<string>();
  readonly pickHistory = output<void>();

  protected capitalized(text: string): string {
    return text.charAt(0).toUpperCase() + text.slice(1);
  }

  protected statusLabel(status: RulesTreeStatus): string {
    return status ? STATUS_LABELS[status] : '';
  }

  protected isSection(id: string): boolean {
    const s = this.selection();
    return s.kind === 'section' && s.id === id;
  }

  protected isBranch(id: string): boolean {
    const s = this.selection();
    return s.kind === 'branch' && s.branchId === id;
  }

  protected isCoverage(branchId: string, coverageId: string): boolean {
    const s = this.selection();
    return s.kind === 'coverage' && s.branchId === branchId && s.coverageId === coverageId;
  }
}
