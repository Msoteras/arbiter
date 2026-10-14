import { Injectable, signal } from '@angular/core';

export type ToastTone = 'ok' | 'warning' | 'danger' | 'info';

/** A single follow-up the toast offers, e.g. going to the next step. Running it closes the toast. */
export interface ToastAction {
  label: string;
  run: () => void;
}

export interface ToastOptions {
  /** A second line under the message: what to do, or more detail. */
  description?: string;
  action?: ToastAction;
}

export interface Toast {
  id: number;
  message: string;
  description?: string;
  tone: ToastTone;
  action?: ToastAction;
  /** Set while it animates out; the stack keeps it on screen until then. */
  leaving: boolean;
}

/** How long the exit animation runs before the toast leaves the DOM. */
export const TOAST_EXIT_MS = 200;

interface Timer {
  handle: ReturnType<typeof setTimeout> | null;
  remaining: number;
  startedAt: number;
}

/**
 * Holds the toast queue; `app-toast-stack`, mounted once in `app.html`, renders it. Timers pause
 * while the stack is expanded (hover or focus), so a toast someone is reading doesn't vanish.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private readonly _toasts = signal<Toast[]>([]);
  readonly toasts = this._toasts.asReadonly();
  private nextId = 0;
  private readonly timers = new Map<number, Timer>();
  private paused = false;

  show(
    message: string,
    tone: ToastTone = 'danger',
    durationMs = 6000,
    options: ToastOptions = {},
  ): void {
    const id = this.nextId++;
    this._toasts.update((list) => [
      ...list,
      {
        id,
        message,
        tone,
        description: options.description,
        action: options.action,
        leaving: false,
      },
    ]);
    const timer: Timer = { handle: null, remaining: durationMs, startedAt: Date.now() };
    this.timers.set(id, timer);
    if (!this.paused) {
      this.start(id, timer);
    }
  }

  dismiss(id: number): void {
    const timer = this.timers.get(id);
    if (timer?.handle) {
      clearTimeout(timer.handle);
    }
    this.timers.delete(id);
    this._toasts.update((list) => list.map((t) => (t.id === id ? { ...t, leaving: true } : t)));
    setTimeout(() => this._toasts.update((list) => list.filter((t) => t.id !== id)), TOAST_EXIT_MS);
  }

  /** While the stack is expanded: every timer stops where it was. */
  pause(): void {
    if (this.paused) {
      return;
    }
    this.paused = true;
    for (const timer of this.timers.values()) {
      if (timer.handle) {
        clearTimeout(timer.handle);
        timer.handle = null;
        timer.remaining -= Date.now() - timer.startedAt;
      }
    }
  }

  resume(): void {
    if (!this.paused) {
      return;
    }
    this.paused = false;
    for (const [id, timer] of this.timers) {
      this.start(id, timer);
    }
  }

  private start(id: number, timer: Timer): void {
    timer.startedAt = Date.now();
    timer.handle = setTimeout(() => this.dismiss(id), Math.max(timer.remaining, 0));
  }
}
