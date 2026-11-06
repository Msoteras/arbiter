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
                <ng-container *ngTemplateOutlet="dot; context: { $implicit: s.status }" />
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
                <div class="row">
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
                    [class.active]="isBranch(b.id)"
                    [attr.aria-current]="isBranch(b.id) ? 'page' : null"
                    (click)="pickBranch.emit(b.id)"
                  >
                    <span class="label">{{ b.name }}</span>
                    @if (b.count != null) {
                      <span class="count tabular">{{ b.count }}</span>
                    }
                    <ng-container *ngTemplateOutlet="dot; context: { $implicit: b.status }" />
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
                          <ng-container *ngTemplateOutlet="dot; context: { $implicit: c.status }" />
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
    </nav>

    <ng-template #dot let-status>
      @if (status) {
        <span class="dot" [class.is-required]="status === 'required'" aria-hidden="true"></span>
        <span class="sr-only">({{ statusLabel(status) }})</span>
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
      gap: var(--space-4);
      padding: var(--space-3);
      border: 1px solid var(--border-default);
      border-radius: var(--radius-card);
      background: var(--surface);
    }
    .group {
      display: flex;
      flex-direction: column;
      gap: var(--space-1);
    }
    .heading {
      margin: 0 0 var(--space-1);
      padding: 0 var(--space-2);
    }
    .list,
    .children {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: var(--space-1);
    }
    .children {
      margin-left: var(--space-5);
      padding-left: var(--space-2);
      border-left: 1px solid var(--border-subtle);
    }
    .row {
      display: flex;
      align-items: center;
      gap: var(--space-1);
    }
    .item {
      flex: 1 1 auto;
      min-width: 0;
      width: 100%;
      display: flex;
      align-items: center;
      gap: var(--space-2);
      min-height: var(--space-6);
      padding: var(--space-1) var(--space-2);
      border: 0;
      border-radius: var(--radius-ctl);
      background: none;
      font: inherit;
      font-size: var(--font-size-sm);
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
    .item.active {
      background: var(--selected-bg);
      color: var(--accent-fg);
      font-weight: var(--font-weight-medium);
    }
    .item.add {
      color: var(--text-secondary);
    }
    .label {
      flex: 1 1 auto;
      min-width: 0;
      overflow-wrap: anywhere;
    }
    .count {
      font-size: var(--font-size-xs);
      color: var(--text-tertiary);
    }
    .caret {
      flex: 0 0 auto;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: var(--space-5);
      height: var(--space-6);
      padding: 0;
      border: 0;
      border-radius: var(--radius-ctl);
      background: none;
      color: var(--text-tertiary);
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
    /* The dot of app-tabs: neutral when incomplete, warning when something is missing. */
    .dot {
      flex: 0 0 auto;
      display: inline-block;
      width: var(--space-2);
      height: var(--space-2);
      border-radius: var(--radius-pill);
      background: var(--text-tertiary);
    }
    .dot.is-required {
      background: var(--status-warning);
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

  readonly pickSection = output<string>();
  readonly pickBranch = output<string>();
  readonly toggleBranch = output<string>();
  readonly pickCoverage = output<{ branchId: string; coverageId: string }>();
  readonly addCoverage = output<string>();

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
