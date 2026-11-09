import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import { StatusTransition } from '../../../core/models/case';
import {
  CaseStatus,
  StatusMoves,
  caseStatusLabel,
  caseStatusTone,
} from '../../../core/models/case-status';
import { formatDateTime } from '../../../core/util/datetime';
import { ChipGroupComponent, ChipOption } from '../chip-group/chip-group.component';
import {
  LifecycleNode,
  NODE_CENTER,
  NODE_HEIGHT,
  NODE_WIDTH,
  NO_VERDICT_GROUP,
  PORT,
  Point,
  ROUTES,
  START,
  START_POINT,
  VERDICT_GROUP,
  VIEW_BOX,
  midpointOf,
  pointsOf,
  polylineLength,
  roundedPath,
  routeOf,
  walkPoints,
} from './case-lifecycle-layout';

/** `out` and `in` follow the picked status; `path` is a walk, sample or real. */
type LineState = '' | 'out' | 'in' | 'path';

interface Line {
  key: string;
  d: string;
  state: LineState;
  /** The flow runs against the way the line is stored. */
  back: boolean;
  /** Times a real case went through it, shown when more than once. */
  count: number;
  mid: Point;
  title: string;
}

interface SampleWalk {
  value: string;
  label: string;
  steps: LifecycleNode[];
}

const SAMPLE_WALKS: SampleWalk[] = [
  {
    value: 'direct',
    label: 'Aprobado directo',
    steps: [START, 'PENDING_CLASSIFICATION', 'PENDING_ANALYST_REVIEW', 'APPROVED'],
  },
  {
    value: 'expert',
    label: 'Con peritaje',
    steps: [
      START,
      'PENDING_CLASSIFICATION',
      'PENDING_ANALYST_REVIEW',
      'PENDING_EXPERT_REPORT',
      'PENDING_ANALYST_REVIEW',
      'APPROVED',
    ],
  },
  {
    value: 'docs',
    label: 'Pedido de documentación',
    steps: [
      START,
      'PENDING_CLASSIFICATION',
      'PENDING_ANALYST_REVIEW',
      'AWAITING_DOCUMENTATION',
      'PENDING_CLASSIFICATION',
      'PENDING_ANALYST_REVIEW',
      'REJECTED',
    ],
  },
  {
    value: 'reopen',
    label: 'Reapertura',
    steps: [
      START,
      'PENDING_CLASSIFICATION',
      'PENDING_ANALYST_REVIEW',
      'REJECTED',
      'PENDING_ANALYST_REVIEW',
      'APPROVED',
    ],
  },
];
const ALL = 'all';

const ACTOR_LABELS: Record<StatusTransition['actor'], string> = {
  SYSTEM: 'Arbiter',
  INSURED: 'Asegurado',
  ANALYST: 'Analista',
  REFERENT: 'Referente',
};

/** Speed of the sample walk's dot, in diagram units per second. */
const WALK_SPEED = 320;

/**
 * The case state machine as a diagram. Without `history` it is a reference: pick a status to see
 * where it goes and where it comes from, or a sample walk. With `history` it shows one case: the
 * path it took and where it is now, never the moves still open (some may not apply to it).
 */
@Component({
  selector: 'app-case-lifecycle',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ChipGroupComponent],
  template: `
    @if (showWalks()) {
      <app-chip-group
        class="walks"
        size="sm"
        ariaLabel="Recorrido"
        [options]="walkOptions"
        [value]="walk()"
        (valueChange)="pickWalk($event)"
      />
    }
    <div class="canvas" [class.case]="caseMode()">
      <svg
        class="diagram"
        [attr.viewBox]="'0 0 ' + viewBox.width + ' ' + viewBox.height"
        [attr.role]="caseMode() ? null : 'group'"
        [attr.aria-label]="caseMode() ? null : 'Estados del expediente'"
        [attr.aria-hidden]="caseMode() ? 'true' : null"
      >
        <defs>
          <marker
            id="lc-arrow"
            viewBox="0 0 8 8"
            refX="6"
            refY="4"
            markerWidth="7"
            markerHeight="7"
            orient="auto"
          >
            <path d="M0,0 L8,4 L0,8 z" class="arrow" />
          </marker>
          <marker
            id="lc-arrow-active"
            viewBox="0 0 8 8"
            refX="6"
            refY="4"
            markerWidth="7"
            markerHeight="7"
            orient="auto"
          >
            <path d="M0,0 L8,4 L0,8 z" class="arrow active" />
          </marker>
          <marker
            id="lc-arrow-in"
            viewBox="0 0 8 8"
            refX="6"
            refY="4"
            markerWidth="7"
            markerHeight="7"
            orient="auto"
          >
            <path d="M0,0 L8,4 L0,8 z" class="arrow in" />
          </marker>
        </defs>

        <rect
          class="group"
          rx="10"
          [attr.x]="verdictGroup.x"
          [attr.y]="verdictGroup.y"
          [attr.width]="verdictGroup.width"
          [attr.height]="verdictGroup.height"
        />
        <text class="group-label" [attr.x]="verdictGroup.x + 10" [attr.y]="verdictGroup.y + 16">
          Con veredicto
        </text>
        <rect
          class="group"
          rx="10"
          [attr.x]="noVerdictGroup.x"
          [attr.y]="noVerdictGroup.y"
          [attr.width]="noVerdictGroup.width"
          [attr.height]="noVerdictGroup.height"
        />
        <text class="group-label" [attr.x]="noVerdictGroup.x + 10" [attr.y]="noVerdictGroup.y + 16">
          Sin veredicto
        </text>

        @for (line of lines(); track line.key) {
          <path class="edge" [class]="line.state" [attr.d]="line.d" />
          @if (line.state !== '') {
            <path class="flow" [class]="line.state" [class.back]="line.back" [attr.d]="line.d" />
          }
          @if (line.title) {
            <path class="hit" [attr.d]="line.d">
              <title>{{ line.title }}</title>
            </path>
          }
        }
        @for (port of ports(); track port.id) {
          <path
            class="stub"
            [class]="port.state"
            [attr.d]="port.d"
            [attr.marker-end]="
              'url(#' +
              (port.state === 'in'
                ? 'lc-arrow-in'
                : port.state === ''
                  ? 'lc-arrow'
                  : 'lc-arrow-active') +
              ')'
            "
          />
          <circle
            class="port"
            [class]="port.state"
            r="3.4"
            [attr.cx]="port.cx"
            [attr.cy]="port.cy"
          />
        }
        @for (line of lines(); track line.key) {
          @if (line.count > 1) {
            <g
              class="count"
              [attr.transform]="'translate(' + line.mid[0] + ' ' + line.mid[1] + ')'"
            >
              <rect x="-13" y="-9" width="26" height="18" rx="9" />
              <text text-anchor="middle" y="4">×{{ line.count }}</text>
            </g>
          }
        }

        <circle class="start" r="7" [attr.cx]="start[0]" [attr.cy]="start[1]" />
        <text class="start-label" text-anchor="middle" [attr.x]="start[0]" [attr.y]="start[1] - 14">
          Denuncia
        </text>

        @for (node of nodes(); track node.id) {
          <g
            class="node"
            [class.selected]="node.selected"
            [class.visited]="node.visited"
            [class.dim]="node.dim"
            [class.interactive]="!caseMode()"
            [attr.tabindex]="caseMode() ? null : 0"
            [attr.role]="caseMode() ? null : 'button'"
            [attr.aria-pressed]="caseMode() ? null : node.selected"
            [attr.aria-label]="caseMode() ? null : node.ariaLabel"
            (click)="pick(node.id)"
            (keydown.enter)="pick(node.id)"
            (keydown.space)="$event.preventDefault(); pick(node.id)"
          >
            <rect
              class="box"
              rx="8"
              [attr.x]="node.x"
              [attr.y]="node.y"
              [attr.width]="nodeWidth"
              [attr.height]="nodeHeight"
            />
            <circle
              class="tone"
              r="4"
              [attr.data-tone]="node.tone"
              [attr.cx]="node.x + 13"
              [attr.cy]="node.y + nodeHeight / 2"
            />
            <text [attr.x]="node.x + 24" [attr.y]="node.y + nodeHeight / 2 + 4">
              {{ node.label }}
            </text>
            @if (node.count !== null) {
              <g
                class="count-pill"
                [attr.transform]="'translate(' + (node.x + nodeWidth) + ' ' + node.y + ')'"
              >
                <rect
                  [attr.x]="-pillWidth(node.count) + 6"
                  y="-9"
                  [attr.width]="pillWidth(node.count)"
                  height="18"
                  rx="9"
                />
                <text text-anchor="middle" y="4" [attr.x]="-pillWidth(node.count) / 2 + 6">
                  {{ node.count }}
                </text>
              </g>
            }
          </g>
        }

        @if (walkMotion(); as motion) {
          @for (m of [motion]; track m.id) {
            <circle class="traveler" r="5">
              <animateMotion
                [attr.dur]="m.dur + 's'"
                repeatCount="indefinite"
                [attr.path]="m.d"
                keyPoints="0;1;1"
                keyTimes="0;0.82;1"
                calcMode="linear"
              />
            </circle>
          }
        }
      </svg>
    </div>

    @if (caseMode()) {
      <ol class="sr-only">
        @for (step of caseSteps(); track $index) {
          @if (step !== start$) {
            <li>{{ label(step) }}</li>
          }
        }
      </ol>
    } @else if (countMode()) {
      <ul class="sr-only">
        @for (node of nodes(); track node.id) {
          <li>{{ node.label }}: {{ node.count }}</li>
        }
      </ul>
    } @else {
      <ul class="sr-only">
        @for (s of accessibleMoves(); track s.from) {
          <li>{{ s.text }}</li>
        }
      </ul>
    }
  `,
  styles: `
    :host {
      display: grid;
      gap: var(--space-3);
      min-width: 0;
    }
    .walks {
      justify-self: start;
    }
    .canvas {
      overflow-x: auto;
      border: 1px solid var(--border-subtle);
      border-radius: var(--radius-ctl);
      background-color: var(--diagram-canvas);
      background-image: radial-gradient(circle, var(--diagram-grid-dot) 1px, transparent 1.4px);
      background-size: var(--space-4) var(--space-4);
      background-position: var(--space-2) var(--space-2);
    }
    .diagram {
      display: block;
      width: 100%;
      min-width: calc(var(--space-7) * 22.5);
      height: auto;
      font-family: var(--font-sans);
    }

    .group {
      fill: none;
      stroke: var(--border-strong);
      stroke-dasharray: 3 4;
    }
    .group-label {
      font-size: var(--font-size-xs);
      fill: var(--text-muted);
    }

    .edge {
      fill: none;
      stroke: var(--diagram-edge);
      stroke-width: 1.5;
      stroke-dasharray: 1.5 4.5;
      stroke-linecap: round;
    }
    .edge.out,
    .edge.in,
    .edge.path {
      stroke-dasharray: none;
      stroke-width: 2;
    }
    .edge.out,
    .edge.path {
      stroke: var(--diagram-edge-active);
    }
    .edge.in {
      stroke: var(--border-strong);
    }
    .flow {
      fill: none;
      stroke-width: 2.4;
      stroke-linecap: round;
      stroke-dasharray: 0.1 14;
      animation: flow 1.1s linear infinite;
      pointer-events: none;
    }
    .flow.out,
    .flow.path {
      stroke: var(--accent-fg);
    }
    .flow.in {
      stroke: var(--text-muted);
    }
    .flow.back {
      animation-direction: reverse;
    }
    /* On a case the pulse only runs while the pointer is over the diagram. */
    .case .flow {
      opacity: 0;
      transition: opacity 0.2s;
    }
    .case:hover .flow {
      opacity: 1;
    }
    @keyframes flow {
      to {
        stroke-dashoffset: -14.1;
      }
    }
    .hit {
      fill: none;
      stroke: transparent;
      stroke-width: 12;
      pointer-events: stroke;
    }

    .stub {
      fill: none;
      stroke: var(--border-strong);
      stroke-width: 1.5;
    }
    .stub.out,
    .stub.path {
      stroke: var(--accent-fg);
      stroke-width: 2;
    }
    .stub.in {
      stroke: var(--text-muted);
      stroke-width: 2;
    }
    .port {
      fill: var(--surface);
      stroke: var(--border-strong);
      stroke-width: 1.2;
    }
    .port.out,
    .port.path {
      stroke: var(--accent-fg);
    }
    .port.in {
      stroke: var(--text-muted);
    }
    .arrow {
      fill: var(--border-strong);
    }
    .arrow.active {
      fill: var(--accent-fg);
    }
    .arrow.in {
      fill: var(--text-muted);
    }

    .count rect {
      fill: var(--surface);
      stroke: var(--accent-fg);
    }
    .count text {
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-bold);
      fill: var(--accent-fg);
    }

    .start {
      fill: var(--text-primary);
    }
    .start-label {
      font-size: var(--font-size-sm);
      fill: var(--text-muted);
    }

    .node .box {
      fill: var(--surface);
      stroke: var(--border-default);
      stroke-width: 1;
      transition:
        stroke 0.2s,
        fill 0.2s;
    }
    .node.interactive {
      cursor: pointer;
    }
    .node.interactive:hover .box {
      stroke: var(--border-strong);
    }
    .node.visited .box {
      stroke: var(--accent-fg);
    }
    .node.selected .box {
      fill: var(--selected-bg);
      stroke: var(--accent-fg);
      stroke-width: 1.6;
    }
    .node.dim {
      opacity: 0.45;
    }
    .node text {
      font-size: var(--font-size-sm);
      fill: var(--text-primary);
      pointer-events: none;
    }
    .count-pill rect {
      fill: var(--action-primary-bg);
    }
    .node .count-pill text {
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-bold);
      font-variant-numeric: tabular-nums;
      fill: var(--action-primary-fg);
    }
    .node:focus {
      outline: none;
    }
    .node:focus-visible .box {
      stroke: var(--border-focus);
      stroke-width: 2.4;
    }
    .tone {
      fill: var(--text-muted);
    }
    .tone[data-tone='ok'] {
      fill: var(--status-ok);
    }
    .tone[data-tone='warning'] {
      fill: var(--status-warning);
    }
    .tone[data-tone='danger'] {
      fill: var(--status-danger);
    }
    .tone[data-tone='info'] {
      fill: var(--status-info);
    }
    .traveler {
      fill: var(--accent-fg);
    }

    @media (prefers-reduced-motion: reduce) {
      .flow,
      .traveler {
        display: none;
      }
      .node .box {
        transition: none;
      }
    }
  `,
})
export class CaseLifecycleComponent {
  /** The machine as cases-service enforces it (`CaseService.statusMoves()`). */
  readonly transitions = input.required<readonly StatusMoves[]>();
  /** One case's history, oldest first. When set, the diagram shows that case and nothing is picked. */
  readonly history = input<readonly StatusTransition[] | null>(null);
  readonly currentStatus = input<string | null>(null);
  /**
   * Cases in each status, by CaseStatus name. When set (and there is no `history`), every status
   * shows its count and nothing is picked; a status with no cases is dimmed.
   */
  readonly counts = input<Readonly<Record<string, number>> | null>(null);

  protected readonly viewBox = VIEW_BOX;
  protected readonly nodeWidth = NODE_WIDTH;
  protected readonly nodeHeight = NODE_HEIGHT;
  protected readonly verdictGroup = VERDICT_GROUP;
  protected readonly noVerdictGroup = NO_VERDICT_GROUP;
  protected readonly start = START_POINT;
  protected readonly start$ = START;

  protected readonly walkOptions: ChipOption[] = [
    { value: ALL, label: 'Todos' },
    ...SAMPLE_WALKS.map(({ value, label }) => ({ value, label })),
  ];
  protected readonly walk = signal(ALL);
  /** Null until a status is picked: the reference then shows review, the counts show none. */
  private readonly picked = signal<CaseStatus | null>(null);
  protected readonly selected = computed<CaseStatus | null>(
    () => this.picked() ?? (this.countMode() ? null : 'PENDING_ANALYST_REVIEW'),
  );

  protected readonly caseMode = computed(() => this.history() != null);
  protected readonly countMode = computed(() => !this.caseMode() && this.counts() != null);
  /** Sample walks belong to the reference only. */
  protected readonly showWalks = computed(() => !this.caseMode() && !this.countMode());

  /** Every move, plus the report that opens the case. */
  private readonly moves = computed(() => {
    const keys = new Set<string>([`${START}>PENDING_CLASSIFICATION`]);
    for (const m of this.transitions()) {
      if (!(m.from in NODE_CENTER)) {
        continue;
      }
      for (const to of m.to) {
        if (to in NODE_CENTER) {
          keys.add(`${m.from}>${to}`);
        }
      }
    }
    return keys;
  });

  /** Status changes only: assignments and notes leave a row with the same status on both ends. */
  private readonly realMoves = computed(() =>
    (this.history() ?? []).filter((h) => h.fromStatus !== h.toStatus),
  );

  protected readonly caseSteps = computed<LifecycleNode[]>(() => {
    const rows = this.realMoves();
    if (rows.length === 0) {
      const current = this.currentStatus();
      return current && current in NODE_CENTER ? [START, current as CaseStatus] : [START];
    }
    const first = rows[0].fromStatus;
    const head: LifecycleNode[] = first && first in NODE_CENTER ? [first as CaseStatus] : [START];
    return [...head, ...rows.map((h) => h.toStatus as CaseStatus)];
  });

  private readonly activeSteps = computed<LifecycleNode[] | null>(() => {
    if (this.caseMode()) {
      return this.caseSteps();
    }
    return SAMPLE_WALKS.find((w) => w.value === this.walk())?.steps ?? null;
  });

  /** How many times the walk used each line, either way. */
  private readonly walkCounts = computed(() => {
    const counts = new Map<string, number>();
    const steps = this.activeSteps();
    if (steps) {
      for (let i = 0; i < steps.length - 1; i++) {
        const { key } = routeOf(steps[i], steps[i + 1]);
        counts.set(key, (counts.get(key) ?? 0) + 1);
      }
    }
    return counts;
  });

  /** Who moved the case along each line and when, for its tooltip. */
  private readonly lineTitles = computed(() => {
    const titles = new Map<string, string[]>();
    for (const h of this.realMoves()) {
      const { key } = routeOf(
        (h.fromStatus as CaseStatus | null) ?? START,
        h.toStatus as CaseStatus,
      );
      const text = `${formatDateTime(h.changedAt)} · ${ACTOR_LABELS[h.actor] ?? h.actor}`;
      titles.set(key, [...(titles.get(key) ?? []), text]);
    }
    return titles;
  });

  protected readonly lines = computed<Line[]>(() => {
    const moves = this.moves();
    const steps = this.activeSteps();
    const counts = this.walkCounts();
    const titles = this.lineTitles();
    const selected = this.selected();
    const drawn = new Set<string>();
    const lines: Line[] = [];
    for (const move of moves) {
      const [from, to] = move.split('>') as [LifecycleNode, LifecycleNode];
      const { key } = routeOf(from, to);
      if (drawn.has(key)) {
        continue;
      }
      drawn.add(key);
      const [kf, kt] = key.split('>');
      const both = moves.has(`${kt}>${kf}`);
      let state: LineState = '';
      let back = false;
      if (steps) {
        state = counts.has(key) ? 'path' : '';
      } else if (kf === selected) {
        state = 'out';
      } else if (kt === selected) {
        // A two-way line leaves the picked status too: it flows out, against the drawing.
        state = both ? 'out' : 'in';
        back = both;
      }
      const points = pointsOf(key);
      lines.push({
        key,
        d: roundedPath(points),
        state,
        back,
        count: this.caseMode() ? (counts.get(key) ?? 0) : 0,
        mid: midpointOf(points),
        title: (titles.get(key) ?? []).join('\n'),
      });
    }
    return lines;
  });

  /** Each status's single arrow, coloured by the line that reaches it. */
  protected readonly ports = computed(() => {
    const moves = this.moves();
    const steps = this.activeSteps();
    const selected = this.selected();
    const state = new Map<string, LineState>();
    if (steps) {
      for (const s of steps.slice(1)) {
        state.set(s, 'path');
      }
    } else if (selected) {
      for (const move of moves) {
        const [from, to] = move.split('>');
        if (from === selected) {
          state.set(to, 'out');
        } else if (to === selected && !state.has(to)) {
          state.set(to, 'in');
        }
      }
    }
    return (Object.keys(PORT) as CaseStatus[])
      .filter((id) => [...moves].some((m) => m.endsWith(`>${id}`)))
      .map((id) => {
        const { join, tip } = PORT[id];
        return {
          id,
          d: `M ${join[0]} ${join[1]} L ${tip[0]} ${tip[1]}`,
          cx: join[0],
          cy: join[1],
          state: state.get(id) ?? '',
        };
      });
  });

  protected readonly nodes = computed(() => {
    const steps = this.activeSteps();
    const onPath = new Set<string>(steps ?? []);
    const selected = this.selected();
    const current = this.currentStatus();
    const related = new Set<string>(selected ? [selected] : []);
    for (const move of this.moves()) {
      const [from, to] = move.split('>');
      if (from === selected) related.add(to);
      if (to === selected) related.add(from);
    }
    return (Object.keys(NODE_CENTER) as CaseStatus[]).map((id) => {
      const [cx, cy] = NODE_CENTER[id];
      const isSelected = this.caseMode() ? id === current : !steps && id === selected;
      const count = this.countMode() ? (this.counts()?.[id] ?? 0) : null;
      return {
        id,
        label: caseStatusLabel(id),
        tone: caseStatusTone(id),
        x: cx - NODE_WIDTH / 2,
        y: cy - NODE_HEIGHT / 2,
        selected: isSelected,
        ariaLabel: count === null ? caseStatusLabel(id) : `${caseStatusLabel(id)}: ${count}`,
        visited: !!steps && onPath.has(id) && !isSelected,
        dim: steps ? !onPath.has(id) : selected ? !related.has(id) : count !== null && count === 0,
        count,
      };
    });
  });

  /** The dot that walks a sample path. Keyed by the walk, so picking another restarts it. */
  protected readonly walkMotion = computed(() => {
    if (!this.showWalks()) {
      return null;
    }
    const walk = SAMPLE_WALKS.find((w) => w.value === this.walk());
    if (!walk) {
      return null;
    }
    const points = walkPoints(walk.steps);
    const travel = polylineLength(points) / WALK_SPEED;
    return { id: walk.value, d: roundedPath(points), dur: (travel / 0.82).toFixed(2) };
  });

  protected readonly accessibleMoves = computed(() =>
    this.transitions()
      .filter((m) => m.to.length > 0)
      .map((m) => ({
        from: m.from,
        text: `${caseStatusLabel(m.from)}: pasa a ${m.to.map(caseStatusLabel).join(', ')}.`,
      })),
  );

  protected pick(id: CaseStatus): void {
    if (this.caseMode()) {
      return;
    }
    // On the counts a second click clears the pick; the reference always keeps one.
    this.picked.set(this.countMode() && this.selected() === id ? null : id);
    this.walk.set(ALL);
  }

  protected pillWidth(count: number): number {
    return 12 + 7 * String(count).length;
  }

  protected pickWalk(value: string): void {
    this.walk.set(value);
  }

  protected label(step: LifecycleNode): string {
    return caseStatusLabel(step);
  }
}

/** Moves that the layout draws straight because it has no route for them: a gap to fix here. */
export function unroutedMoves(transitions: readonly StatusMoves[]): string[] {
  return transitions.flatMap((m) =>
    m.to
      .filter((to) => !ROUTES[`${m.from}>${to}`] && !ROUTES[`${to}>${m.from}`])
      .map((to) => `${m.from}>${to}`),
  );
}
