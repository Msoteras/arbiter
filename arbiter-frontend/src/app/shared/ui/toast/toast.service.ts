import { Injectable, signal } from '@angular/core';

export type ToastTone = 'ok' | 'warning' | 'danger' | 'info';

export interface Toast {
  id: number;
  message: string;
  tone: ToastTone;
}

/**
 * Avisos efímeros para errores/confirmaciones que no ameritan quedar clavados en la pantalla como
 * texto fijo (ej. "El backend dio error" al lado de un botón). `app-toast-stack`, montado una sola
 * vez en `app.html`, es quien los dibuja — este service solo lleva la cola.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private readonly _toasts = signal<Toast[]>([]);
  readonly toasts = this._toasts.asReadonly();
  private nextId = 0;

  show(message: string, tone: ToastTone = 'danger', durationMs = 6000): void {
    const id = this.nextId++;
    this._toasts.update((list) => [...list, { id, message, tone }]);
    setTimeout(() => this.dismiss(id), durationMs);
  }

  dismiss(id: number): void {
    this._toasts.update((list) => list.filter((t) => t.id !== id));
  }
}
