import { Injectable, signal } from '@angular/core';

/**
 * Estado del modal de "Nueva denuncia" del asegurado. La denuncia se abre como un diálogo sobre
 * el portal (no como una página aparte), y se dispara desde varios lugares —la barra superior, la
 * tarjeta del inicio, el botón de "Mis siniestros"—, así que el open/close vive en un servicio
 * compartido en vez de en una pantalla puntual. El modal en sí lo hostea el shell de la app.
 */
@Injectable({ providedIn: 'root' })
export class NewClaimModalService {
  private readonly _open = signal(false);
  readonly isOpen = this._open.asReadonly();

  open(): void {
    this._open.set(true);
  }

  close(): void {
    this._open.set(false);
  }
}
