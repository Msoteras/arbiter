import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  NgZone,
  afterNextRender,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Subscription, interval, switchMap } from 'rxjs';

import { DocumentTypeService } from '../../core/services/document-type.service';
import {
  EngineCase,
  StageActor,
  StageIcon,
  engineResultLabel,
  engineResultTone,
  isQueued,
  stageDef,
} from '../../core/models/engine';
import { StatusTone } from '../../core/models/status-tone';
import { BadgeComponent } from '../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import { EmptyStateComponent } from '../../shared/ui/empty-state/empty-state.component';
import { LogoComponent } from '../../shared/ui/logo/logo.component';
import { EngineBoardService } from './engine-board.service';
import {
  EMPTY_STATE,
  EngineState,
  POOL_MAX,
  applyStep,
  estimateProgress,
  expireHolds,
  fromBoard,
  isPastGate,
  resync,
} from './engine-state';

const ICONS: Record<StageIcon, string> = {
  policy: 'M12 3l7 3v5c0 4.6-3 8.2-7 10-4-1.8-7-5.4-7-10V6z M9 11.5h6 M9 14.5h4',
  history: 'M4.5 12a7.5 7.5 0 1 0 2.2-5.3 M4.5 4.5v3.2h3.2 M12 8.5V12l2.6 1.6',
  rules: 'M3.5 15.5L15.5 3.5l5 5-12 12z M7.5 11.5l1.8 1.8 M10.5 8.5l1.8 1.8 M13.5 5.5l1.8 1.8',
  exclusion: 'M4 5h16l-6.2 7.2V18l-3.6 2v-7.8z',
  document: 'M7 3h7l4 4v14H7z M14 3v4h4 M10 12h5 M10 15.5h5',
  fastTrack: 'M4 6.5l6.5 5.5L4 17.5z M12.5 6.5l6.5 5.5-6.5 5.5z',
  schedule: 'M9 3.5h6v3H9z M7 5H5v16h14V5h-2 M8.5 13l2.2 2.2 4.3-4.4',
  model: 'M12 3l8 4.5v9L12 21l-8-4.5v-9z M4.5 7.7L12 12l7.5-4.3 M12 12v8.6',
  image:
    'M4 5h16v14H4z M4 16l4.5-4.5 4 4 2.5-2.5L20 18 M15.5 7.4a1.2 1.2 0 1 0 0 2.4a1.2 1.2 0 1 0 0-2.4',
  risk: 'M4 16a8 8 0 0 1 16 0 M12 16l3.5-4.5 M4 19.5h16',
  result: 'M6 21V4 M6 4.5h11l-2.2 3.8L17 12H6',
};

const ACTOR_LABEL: Record<StageActor, string> = {
  data: 'Datos',
  rule: 'Reglas',
  model: 'Modelo',
};

/** The resync heals steps lost while the socket was reconnecting. */
const RESYNC_MS = 60_000;
const CLOCK_MS = 250;

// Ring geometry, in CSS px of the 3D scene. Three cards face the viewer; the rest turn away.
const PERSPECTIVE = 2000;
const FAN_Z = 400;
const RING_X = 470;
const RING_Z = 400;
const RING_LIFT = 250;
const WIDE: [number, number] = [340, 136];
const TALL: [number, number] = [232, 282];
// Cell of the pixel fan; "Fino" in the design.
const FAN_CELL = 3;

type Zone = 'queue' | 'engine' | 'pool';
const ZONES: Zone[] = ['queue', 'engine', 'pool'];

interface RingCard {
  caseId: number;
  who: string;
  actor: string;
  running: boolean;
  clock: string;
  icon: string;
  actorClass: StageActor;
  now: string;
  sub: string;
  progress: string;
  focus: boolean;
  result: string;
  tone: StatusTone;
  total: string;
}

interface MiniCard {
  caseId: number;
  who: string;
  label: string;
  clock: string;
  result: string;
  tone: StatusTone;
  depth: string;
  isNew: boolean;
}

/**
 * The classification engine at work, live: the cases being classified turn on a ring in the
 * middle, the queue waits on the left and what was classified today piles up on the right. Every
 * number on it comes from the steps classification-service reports; nothing is simulated.
 */
@Component({
  selector: 'app-engine-board',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    NgTemplateOutlet,
    RouterLink,
    BadgeComponent,
    ButtonComponent,
    EmptyStateComponent,
    LogoComponent,
  ],
  templateUrl: './engine-board.component.html',
  styleUrl: './engine-board.component.scss',
})
export class EngineBoardComponent {
  private readonly service = inject(EngineBoardService);
  private readonly documents = inject(DocumentTypeService);
  private readonly zone = inject(NgZone);
  private readonly destroyRef = inject(DestroyRef);

  private readonly root = viewChild<ElementRef<HTMLElement>>('root');
  private readonly world = viewChild<ElementRef<HTMLElement>>('world');
  private readonly fanPlane = viewChild<ElementRef<HTMLElement>>('fanPlane');
  private readonly fanCanvas = viewChild<ElementRef<HTMLCanvasElement>>('fanCanvas');

  protected readonly loaded = signal(false);
  protected readonly failed = signal(false);
  private readonly state = signal<EngineState>(EMPTY_STATE);
  /** Server time minus browser time, so clocks don't depend on the analyst's machine. */
  private serverOffset = 0;
  private readonly now = signal(Date.now());
  protected readonly zoneIndex = signal(1);
  protected readonly phone = signal(false);

  private readonly pastGate = new Map<number, boolean>();
  private readonly progressFloor = new Map<number, number>();
  private readonly seenInPool = new Set<number>();
  private socket?: Subscription;
  private topic?: string;

  // Motion state, outside Angular: read and written every frame.
  private rot = 0;
  private rotTarget = 0;
  private pan = 0;
  private panTarget = 0;
  private width = 0;
  private reduced = false;
  private drag?: {
    mode: 'rot' | 'pan';
    x: number;
    rot: number;
    pan: number;
    last: number;
    v: number;
  };
  private snapTimer?: ReturnType<typeof setTimeout>;
  private fan?: {
    canvas: HTMLCanvasElement;
    ctx: CanvasRenderingContext2D;
    image: ImageData;
    buffer: Float32Array;
    noise: Float32Array;
    w: number;
    h: number;
    cell: number;
    rgb: [number, number, number];
  };
  private startedAt = performance.now();

  protected readonly queue = computed(() =>
    this.state()
      .active.filter(isQueued)
      .sort((a, b) => a.queuedAt.localeCompare(b.queuedAt)),
  );

  /** Ordered by start, so a card keeps its place on the ring while others come and go. */
  private readonly ringCases = computed(() =>
    this.state()
      .active.filter((c) => !isQueued(c))
      .sort(
        (a, b) =>
          (a.startedAt ?? a.stageAt).localeCompare(b.startedAt ?? b.stageAt) || a.caseId - b.caseId,
      ),
  );

  protected readonly busy = computed(
    () => this.ringCases().filter((c) => !this.state().ending.has(c.caseId)).length,
  );
  protected readonly queued = computed(() => this.queue().length);
  protected readonly doneCount = computed(() => this.state().done.length);

  protected readonly ring = computed<RingCard[]>(() => {
    const now = this.now();
    return this.ringCases().map((c) => this.ringCard(c, now));
  });

  protected readonly queueCards = computed<MiniCard[]>(() => {
    const now = this.now();
    return this.queue().map((c, i) => ({
      caseId: c.caseId,
      who: this.who(c),
      label: `${i + 1}.º en cola`,
      clock: clock(now - Date.parse(c.queuedAt)),
      result: '',
      tone: 'neutral' as StatusTone,
      depth: `translateZ(${-i * 26}px)`,
      isNew: false,
    }));
  });

  protected readonly poolCards = computed<MiniCard[]>(() =>
    this.state()
      .done.slice(0, POOL_MAX)
      .map((c, i) => {
        const z = -((i * 37) % 5) * 45 - Math.floor(i / 5) * 40;
        const y = (((i * 53) % 7) - 3) * 4;
        const isNew = !this.seenInPool.has(c.caseId);
        this.seenInPool.add(c.caseId);
        return {
          caseId: c.caseId,
          who: this.who(c),
          label: c.startedAt
            ? `Terminó en ${duration(Date.parse(c.stageAt) - Date.parse(c.startedAt))}`
            : 'Terminó',
          clock: '',
          result: engineResultLabel(c),
          tone: engineResultTone(c),
          depth: `translate3d(0, ${y}px, ${z}px)`,
          isNew,
        };
      }),
  );

  constructor() {
    const phoneQuery = window.matchMedia('(max-width: 639.98px)');
    this.phone.set(phoneQuery.matches);
    const onPhone = (e: MediaQueryListEvent) => this.phone.set(e.matches);
    phoneQuery.addEventListener('change', onPhone);
    this.destroyRef.onDestroy(() => phoneQuery.removeEventListener('change', onPhone));

    this.load();
    interval(RESYNC_MS)
      .pipe(
        switchMap(() => this.service.board(true)),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (board) => {
          this.serverOffset = Date.parse(board.serverTime) - Date.now();
          this.state.update((s) => resync(s, board.active, board.classifiedToday));
          board.active.forEach((c) => this.trackGate(c));
          this.listen(board.topic);
        },
        error: () => undefined,
      });

    afterNextRender(() => this.zone.runOutsideAngular(() => this.start()));
    this.destroyRef.onDestroy(() => {
      this.socket?.unsubscribe();
      clearTimeout(this.snapTimer);
    });
  }

  protected load(): void {
    this.failed.set(false);
    this.service.board().subscribe({
      next: (board) => {
        this.serverOffset = Date.parse(board.serverTime) - Date.now();
        board.classifiedToday.forEach((c) => this.seenInPool.add(c.caseId));
        board.active.forEach((c) => this.trackGate(c));
        this.state.set(fromBoard(board.active, board.classifiedToday));
        this.loaded.set(true);
        this.listen(board.topic);
      },
      error: () => this.failed.set(true),
    });
  }

  protected goTo(zone: Zone): void {
    this.zoneIndex.set(ZONES.indexOf(zone));
    this.panTarget = (1 - ZONES.indexOf(zone)) * this.width;
  }

  private listen(topic: string): void {
    if (this.topic === topic) return;
    this.topic = topic;
    this.socket?.unsubscribe();
    this.socket = this.service.steps(topic).subscribe((step) => {
      this.trackGate(step);
      this.state.update((s) => applyStep(s, step, this.serverNow()));
    });
  }

  private trackGate(c: EngineCase): void {
    if (c.stage === 'QUEUED') {
      this.pastGate.delete(c.caseId);
      this.progressFloor.delete(c.caseId);
    } else if (isPastGate(c.stage)) {
      this.pastGate.set(c.caseId, true);
    }
  }

  private serverNow(): number {
    return Date.now() + this.serverOffset;
  }

  private who(c: EngineCase): string {
    return [c.insured, c.claimCause].filter(Boolean).join(' · ');
  }

  private ringCard(c: EngineCase, now: number): RingCard {
    const ending = this.state().ending.has(c.caseId);
    const def = stageDef(c.stage);
    const stageElapsed = now - Date.parse(c.stageAt);
    const started = c.startedAt ? Date.parse(c.startedAt) : Date.parse(c.stageAt);
    const estimate = estimateProgress(c, stageElapsed, this.pastGate.get(c.caseId) ?? false);
    const progress = Math.max(estimate, this.progressFloor.get(c.caseId) ?? 0);
    this.progressFloor.set(c.caseId, progress);
    const sub =
      c.stage === 'DOCUMENT' && c.detail
        ? `${this.documents.label(c.detail)} · ${c.itemIndex ?? 1} de ${c.itemCount ?? 1}`
        : clock(stageElapsed);
    return {
      caseId: c.caseId,
      who: this.who(c),
      actor: ending ? 'Terminó' : ACTOR_LABEL[def.actor],
      running: !ending,
      clock: clock((ending ? Date.parse(c.stageAt) : now) - started),
      icon: ICONS[def.icon],
      actorClass: def.actor,
      now: def.text,
      sub,
      progress: (progress * 100).toFixed(2),
      focus: !ending && def.actor === 'model',
      result: engineResultLabel(c),
      tone: engineResultTone(c),
      total: duration(Date.parse(c.stageAt) - started),
    };
  }

  // ---------- motion ----------

  private start(): void {
    const tick = setInterval(() => {
      const now = this.serverNow();
      this.now.set(now);
      const next = expireHolds(this.state(), now);
      if (next !== this.state()) this.state.set(next);
    }, CLOCK_MS);
    this.destroyRef.onDestroy(() => clearInterval(tick));

    let last = performance.now();
    let raf = 0;
    const frame = () => {
      const t = performance.now();
      const dt = Math.min(0.05, (t - last) / 1000);
      last = t;
      this.frame(dt, t);
      raf = requestAnimationFrame(frame);
    };
    raf = requestAnimationFrame(frame);
    this.destroyRef.onDestroy(() => cancelAnimationFrame(raf));

    const reducedQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
    this.reduced = reducedQuery.matches;
    const onReduced = (e: MediaQueryListEvent) => (this.reduced = e.matches);
    reducedQuery.addEventListener('change', onReduced);
    this.destroyRef.onDestroy(() => reducedQuery.removeEventListener('change', onReduced));
  }

  private frame(dt: number, t: number): void {
    const root = this.root()?.nativeElement;
    if (!root || this.phone()) return;
    if (root.clientWidth !== this.width) {
      // Keeps the current zone in view across a resize.
      this.width = root.clientWidth;
      this.panTarget = (1 - this.zoneIndex()) * this.width;
      if (!this.drag) this.pan = this.panTarget;
    }
    const ease = this.reduced ? 1 : 1 - Math.exp(-dt * 7);
    this.rot += (this.rotTarget - this.rot) * ease;
    this.pan += (this.panTarget - this.pan) * ease;

    const world = this.world()?.nativeElement;
    if (world) world.style.transform = `translateX(${this.pan.toFixed(1)}px)`;
    const plane = this.fanPlane()?.nativeElement;
    if (plane) {
      plane.style.transform = `translate(-50%, -50%) translate3d(${(-this.pan * 0.5).toFixed(1)}px, 0, ${-FAN_Z}px)`;
    }
    this.placeRing(root);
    this.drawFan(root, this.reduced ? 0 : (t - this.startedAt) / 1000);
  }

  private placeRing(root: HTMLElement): void {
    const cards = root.querySelectorAll<HTMLElement>('[data-ring]');
    const n = cards.length;
    cards.forEach((el, i) => {
      const phi = (((i + this.rot) / Math.max(n, 1)) * 2 * Math.PI) % (2 * Math.PI);
      const cos = Math.cos(phi);
      const x = RING_X * Math.sin(phi);
      const z = RING_Z * cos - RING_Z;
      const y = (-RING_LIFT * (1 - cos)) / 2;
      const b = clamp((-cos + 0.1) / 0.75, 0, 1);
      const back = b * b * (3 - 2 * b);
      el.style.width = `${(WIDE[0] + (TALL[0] - WIDE[0]) * back).toFixed(1)}px`;
      el.style.height = `${(WIDE[1] + (TALL[1] - WIDE[1]) * back).toFixed(1)}px`;
      el.style.transform = `translate(-50%, -50%) translate3d(${x.toFixed(1)}px, ${y.toFixed(1)}px, ${z.toFixed(1)}px)`;
      el.style.zIndex = String(100 + Math.round(cos * 50));
      el.style.opacity = (0.82 + 0.18 * (1 - back)).toFixed(3);
      el.classList.toggle('tall', back > 0.5);
    });
  }

  /** A slowly turning helix of teal cells behind the ring, drawn on a coarse canvas. */
  private drawFan(root: HTMLElement, t: number): void {
    const canvas = this.fanCanvas()?.nativeElement;
    const plane = this.fanPlane()?.nativeElement;
    if (!canvas || !plane) return;
    const scale = (PERSPECTIVE + FAN_Z) / PERSPECTIVE;
    const cell = Math.round(FAN_CELL * scale);
    const w = Math.ceil((root.clientWidth * scale) / cell);
    const h = Math.ceil((root.clientHeight * scale) / cell);
    let fan = this.fan;
    if (!fan || fan.canvas !== canvas || fan.w !== w || fan.h !== h) {
      const ctx = canvas.getContext('2d');
      if (!ctx || !w || !h) return;
      canvas.width = w;
      canvas.height = h;
      canvas.style.width = `${w * cell}px`;
      canvas.style.height = `${h * cell}px`;
      plane.style.width = `${w * cell}px`;
      plane.style.height = `${h * cell}px`;
      canvas.style.setProperty('--cell', `${cell}px`);
      const noise = new Float32Array(w * h);
      for (let i = 0; i < noise.length; i++) noise[i] = 0.78 + 0.22 * Math.random();
      fan = this.fan = {
        canvas,
        ctx,
        image: ctx.createImageData(w, h),
        buffer: new Float32Array(w * h),
        noise,
        w,
        h,
        cell,
        rgb: accentRgb(root),
      };
    }

    const { buffer, image, noise, rgb } = fan;
    buffer.fill(0);
    const cx = w / 2;
    const cy = h * 0.5;
    const reach = h * 0.62;
    const radius = w * 0.36;
    const turn = t * 0.16;
    const rows = Math.round(h * 0.9);
    for (let blade = 0; blade < 4; blade++) {
      for (let i = 0; i < rows; i++) {
        const s = -1 + (2 * i) / (rows - 1);
        const theta = s * 2.3 + (blade * Math.PI) / 2 + turn;
        const cos = Math.cos(theta);
        const sin = Math.sin(theta);
        const rmax = w * 0.014 + radius * Math.pow(Math.abs(s), 1.35);
        const y = Math.round(cy - s * reach);
        if (y < 0 || y >= h) continue;
        const steps = Math.max(2, Math.round(rmax / 2.4));
        for (let j = 1; j <= steps; j++) {
          const x = Math.round(
            cx + (2 + ((rmax - 2) * (j - 0.5 * ((i + blade) & 1))) / steps) * cos,
          );
          if (x < 0 || x >= w) continue;
          const depth = (sin * j) / steps;
          const level = depth > 0.33 ? 0.95 : depth > -0.33 ? 0.45 : 0.18;
          const p = y * w + x;
          if (level > buffer[p]) buffer[p] = level;
        }
      }
    }
    const data = image.data;
    for (let m = 0; m < buffer.length; m++) {
      const o = m * 4;
      const v = buffer[m];
      if (v) {
        data[o] = rgb[0];
        data[o + 1] = rgb[1];
        data[o + 2] = rgb[2];
        data[o + 3] = Math.round(255 * v * noise[m]);
      } else {
        data[o + 3] = 0;
      }
    }
    fan.ctx.putImageData(image, 0, 0);
  }

  // ---------- gestures ----------

  protected onWheel(e: WheelEvent): void {
    e.preventDefault();
    const d = Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY;
    if (onRing(e.target)) {
      this.rotTarget += d / 240;
      this.snapLater('rot');
    } else {
      this.panTarget = clamp(this.panTarget - d, -this.width, this.width);
      this.snapLater('pan');
    }
  }

  protected onPointerDown(e: PointerEvent): void {
    const target = e.target as Element | null;
    if (target?.closest('button, a')) return;
    this.drag = {
      mode: onRing(target) ? 'rot' : 'pan',
      x: e.clientX,
      rot: this.rotTarget,
      pan: this.panTarget,
      last: 0,
      v: 0,
    };
    (e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId);
  }

  protected onPointerMove(e: PointerEvent): void {
    const d = this.drag;
    if (!d) return;
    const dx = e.clientX - d.x;
    if (d.mode === 'rot') this.rotTarget = d.rot - dx / 260;
    else this.panTarget = clamp(d.pan + dx, -this.width, this.width);
    d.v = dx - d.last;
    d.last = dx;
  }

  protected onPointerUp(): void {
    const d = this.drag;
    if (!d) return;
    this.drag = undefined;
    if (d.mode === 'rot') this.rotTarget = Math.round(this.rotTarget);
    else this.snapPan(d.v);
  }

  protected onKey(e: KeyboardEvent): void {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
    const dir = e.key === 'ArrowLeft' ? -1 : 1;
    if (e.shiftKey) this.rotTarget = Math.round(this.rotTarget) + dir;
    else this.goTo(ZONES[clamp(this.zoneIndex() + dir, 0, 2)]);
    e.preventDefault();
  }

  private snapLater(what: 'rot' | 'pan'): void {
    clearTimeout(this.snapTimer);
    this.snapTimer = setTimeout(() => {
      if (what === 'rot') this.rotTarget = Math.round(this.rotTarget);
      else this.zone.run(() => this.snapPan(0));
    }, 240);
  }

  private snapPan(velocity: number): void {
    if (!this.width) return;
    const at = this.panTarget / this.width + (velocity > 6 ? 0.35 : velocity < -6 ? -0.35 : 0);
    this.zone.run(() => this.goTo(ZONES[1 - clamp(Math.round(at), -1, 1)]));
  }
}

function onRing(target: EventTarget | null): boolean {
  return !!(target as Element | null)?.closest?.('[data-ring]');
}

function clamp(v: number, a: number, b: number): number {
  return v < a ? a : v > b ? b : v;
}

function clock(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  const m = Math.floor(s / 60);
  const r = s % 60;
  return `${m}:${r < 10 ? '0' : ''}${r}`;
}

function duration(ms: number): string {
  const s = Math.max(0, ms) / 1000;
  if (s < 10) return `${s.toFixed(1).replace('.', ',')} s`;
  if (s < 120) return `${Math.round(s)} s`;
  return `${Math.round(s / 60)} min`;
}

/** The brand teal from the theme, so the canvas follows the tokens instead of a literal. */
function accentRgb(el: HTMLElement): [number, number, number] {
  const probe = document.createElement('span');
  probe.style.color = 'var(--accent-fill)';
  el.appendChild(probe);
  const match = getComputedStyle(probe).color.match(/\d+/g) ?? [];
  probe.remove();
  const [r, g, b] = match.map(Number);
  return [r ?? 0, g ?? 0, b ?? 0];
}
