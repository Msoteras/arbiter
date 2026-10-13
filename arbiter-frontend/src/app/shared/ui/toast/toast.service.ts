import { Injectable, signal } from '@angular/core';

export type ToastTone = 'ok' | 'warning' | 'danger' | 'info';

/** A single follow-up the toast offers, e.g. going to the next step. Running it closes the toast. */
export interface ToastAction {
  label: string;
  run: () => void;
}

export interface Toast {
  id: number;
  message: string;
  tone: ToastTone;
  action?: ToastAction;
}

/** Holds the toast queue; `app-toast-stack`, mounted once in `app.html`, renders it. */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private readonly _toasts = signal<Toast[]>([]);
  readonly toasts = this._toasts.asReadonly();
  private nextId = 0;

  show(message: string, tone: ToastTone = 'danger', durationMs = 6000, action?: ToastAction): void {
    const id = this.nextId++;
    this._toasts.update((list) => [...list, { id, message, tone, action }]);
    setTimeout(() => this.dismiss(id), durationMs);
  }

  dismiss(id: number): void {
    this._toasts.update((list) => list.filter((t) => t.id !== id));
  }
}
