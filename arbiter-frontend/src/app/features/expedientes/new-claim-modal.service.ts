import { Injectable, inject, signal } from '@angular/core';
import { NavigationStart, Router } from '@angular/router';
import { filter } from 'rxjs';

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

  /**
   * Abrir el modal no cambia la URL, así que el botón "atrás" del navegador navegaba la pantalla
   * de abajo y dejaba el diálogo encima, ahora sobre una pantalla que no era la que lo abrió. Como
   * el estado vive en un singleton de root, nada lo enteraba de la navegación: se cierra acá.
   *
   * <p>`NavigationStart` y no `NavigationEnd`: el diálogo tiene que irse cuando la navegación
   * arranca, no una vez que la pantalla nueva ya se dibujó abajo.
   */
  constructor() {
    inject(Router)
      .events.pipe(filter((event) => event instanceof NavigationStart))
      .subscribe(() => this.close());
  }

  open(): void {
    this._open.set(true);
  }

  close(): void {
    this._open.set(false);
  }
}
