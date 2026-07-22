import { Injectable, computed, signal } from '@angular/core';

/**
 * Estado de notificaciones del usuario. Hoy solo alimenta el indicador de "sin leer"
 * de la topbar; el productor real (polling / SSE contra el backend) se cablea vía
 * `setUnread` cuando exista el endpoint. `markAllRead` limpia el indicador.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly _unread = signal(0);

  readonly unreadCount = this._unread.asReadonly();
  readonly hasUnread = computed(() => this._unread() > 0);

  setUnread(count: number): void {
    this._unread.set(Math.max(0, count));
  }

  markAllRead(): void {
    this._unread.set(0);
  }
}
