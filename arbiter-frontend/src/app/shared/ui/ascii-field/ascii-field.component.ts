import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  input,
  viewChild,
} from '@angular/core';

import { StatusTone } from '../../../core/models/status-tone';

const GLYPHS = ' ·.:-+=*#%@';
const CELL_W = 11;
const CELL_H = 15;
/** How fast each tone's field drifts: in progress moves more, a settled verdict less. */
const SPEED: Record<StatusTone, number> = {
  neutral: 1.6,
  info: 1.6,
  ok: 0.8,
  warning: 1.1,
  risk: 1,
  danger: 1,
};

/**
 * Decorative field of monospace glyphs: a diagonal ribbon, denser at its core, that drifts and
 * lifts under the pointer. It fills its positioned parent and fades in from the left. When text
 * sits over the field, the parent sets `--ascii-field-fade` (0–1, a share of the width) to where
 * the fade starts, so that text stays readable. Glyphs mix the text color with the tone's.
 * Only animates while on screen, and draws a single still frame under reduced motion.
 */
@Component({
  selector: 'app-ascii-field',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<canvas #canvas aria-hidden="true"></canvas>`,
  styles: `
    :host {
      position: absolute;
      inset: 0;
      pointer-events: none;
    }
    canvas {
      display: block;
      width: 100%;
      height: 100%;
    }
  `,
})
export class AsciiFieldComponent {
  readonly tone = input<StatusTone>('neutral');

  private readonly canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly destroyRef = inject(DestroyRef);

  private ctx: CanvasRenderingContext2D | null = null;
  private width = 0;
  private height = 0;
  private tint: number[] = [0, 0, 0];
  private base: number[] = [0, 0, 0];
  private fadeFrom = 0;
  private pointer = { x: -1, y: -1, weight: 0 };
  private visible = true;
  private frame = 0;
  private readonly start = performance.now();
  private readonly reduceMotion =
    typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;

  constructor() {
    afterNextRender(() => this.setUp());
    effect(() => {
      this.tone();
      this.readColors();
      if (this.reduceMotion) this.draw(0);
    });
  }

  private setUp(): void {
    const canvas = this.canvas().nativeElement;
    this.ctx = canvas.getContext('2d');
    if (!this.ctx) return;
    this.readColors();

    const resize = new ResizeObserver(() => this.resize());
    resize.observe(canvas);
    const onScreen = new IntersectionObserver(([entry]) => (this.visible = entry.isIntersecting));
    onScreen.observe(canvas);

    // The canvas ignores the pointer so the text over it stays selectable; the parent listens.
    const area = this.host.nativeElement.parentElement ?? this.host.nativeElement;
    const move = (e: PointerEvent) => {
      const r = canvas.getBoundingClientRect();
      this.pointer.x = e.clientX - r.left;
      this.pointer.y = e.clientY - r.top;
    };
    const leave = () => (this.pointer.x = -1);
    area.addEventListener('pointermove', move);
    area.addEventListener('pointerleave', leave);

    this.resize();
    if (!this.reduceMotion) this.frame = requestAnimationFrame((t) => this.loop(t));

    this.destroyRef.onDestroy(() => {
      cancelAnimationFrame(this.frame);
      resize.disconnect();
      onScreen.disconnect();
      area.removeEventListener('pointermove', move);
      area.removeEventListener('pointerleave', leave);
    });
  }

  private readColors(): void {
    const el = this.host.nativeElement;
    const tone = this.tone();
    const tintVar = tone === 'neutral' ? '--text-secondary' : `--status-${tone}-text`;
    this.tint = rgbOf(el, tintVar);
    this.base = rgbOf(el, '--text-primary');
  }

  private resize(): void {
    const canvas = this.canvas().nativeElement;
    const rect = canvas.getBoundingClientRect();
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    this.width = rect.width;
    this.height = rect.height;
    canvas.width = Math.round(rect.width * dpr);
    canvas.height = Math.round(rect.height * dpr);
    if (!this.ctx) return;
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const style = getComputedStyle(canvas);
    const fade = parseFloat(style.getPropertyValue('--ascii-field-fade'));
    this.fadeFrom = Number.isFinite(fade) ? Math.min(0.9, Math.max(0, fade)) : 0;
    const mono = style.getPropertyValue('--font-mono').trim() || 'monospace';
    this.ctx.font = `500 11px ${mono}`;
    this.ctx.textBaseline = 'top';
    this.draw(this.reduceMotion ? 0 : (performance.now() - this.start) / 1000);
  }

  private loop(now: number): void {
    const p = this.pointer;
    p.weight += ((p.x >= 0 ? 1 : 0) - p.weight) * 0.06;
    if (this.visible) this.draw((now - this.start) / 1000);
    this.frame = requestAnimationFrame((t) => this.loop(t));
  }

  private draw(t: number): void {
    const ctx = this.ctx;
    const { width: w, height: h, tint, base, pointer } = this;
    if (!ctx || !w || !h) return;
    ctx.clearRect(0, 0, w, h);
    const from = this.fadeFrom;
    const fadeTo = from + (1 - from) * 0.45;
    const sp = SPEED[this.tone()];
    const cols = Math.ceil(w / CELL_W);
    const rows = Math.ceil(h / CELL_H);
    for (let j = 0; j < rows; j++) {
      for (let i = 0; i < cols; i++) {
        const u = i / cols;
        const v = j / rows;
        const along = u * 0.9 + (1 - v) * 0.55;
        const centre = 1.05 - u * 0.95 + Math.sin(u * 4.2 + t * 0.35 * sp) * 0.08;
        const d = v - centre;
        let f = 1.1 * Math.exp(-(d * d) / 0.06) * (0.6 + 0.4 * Math.sin(along * 8 - t * 0.9 * sp));
        f +=
          0.6 *
          Math.exp(
            -((u - 0.8) ** 2 / 0.03 + (v - 0.32 - Math.sin(t * 0.2 * sp) * 0.1) ** 2 / 0.07),
          );
        f *= 0.78 + 0.22 * Math.sin(i * 0.9 + j * 1.3 + t * 1.6 * sp);
        if (pointer.weight > 0.01) {
          const dx = (i * CELL_W - pointer.x) / w;
          const dy = (j * CELL_H - pointer.y) / h;
          f += pointer.weight * 0.7 * Math.exp(-(dx * dx * 6 + dy * dy * 1.6) / 0.02);
        }
        f *= smoothstep(from, fadeTo, u);
        f += (hash(i, j) - 0.5) * 0.14;
        if (f < 0.06) {
          if (hash(j, i) > 0.55 && u > from + 0.04) {
            ctx.fillStyle = `rgba(${base.join(',')},0.07)`;
            ctx.fillText('·', i * CELL_W, j * CELL_H);
          }
          continue;
        }
        const mix = Math.min(1, f * 1.3);
        const rgb = tint.map((c, k) => Math.round(base[k] * (1 - mix) + c * mix));
        ctx.fillStyle = `rgba(${rgb.join(',')},${Math.min(0.9, 0.16 + f * 0.75)})`;
        const glyph = GLYPHS[Math.min(GLYPHS.length - 1, Math.floor(f * GLYPHS.length))];
        ctx.fillText(glyph, i * CELL_W, j * CELL_H);
      }
    }
  }
}

function hash(i: number, j: number): number {
  const s = Math.sin(i * 127.1 + j * 311.7) * 43758.5453;
  return s - Math.floor(s);
}

function smoothstep(a: number, b: number, x: number): number {
  const t = Math.min(1, Math.max(0, (x - a) / (b - a)));
  return t * t * (3 - 2 * t);
}

/** Resolves a color token to RGB through a probe, so any color syntax the token uses works. */
function rgbOf(el: HTMLElement, token: string): number[] {
  const probe = document.createElement('span');
  probe.style.color = `var(${token})`;
  probe.style.display = 'none';
  el.appendChild(probe);
  const match = getComputedStyle(probe).color.match(/\d+(\.\d+)?/g);
  probe.remove();
  return match ? match.slice(0, 3).map(Number) : [0, 0, 0];
}
