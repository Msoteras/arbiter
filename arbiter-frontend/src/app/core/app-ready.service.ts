import { Injectable, signal } from '@angular/core';

/**
 * Marca si la app ya mostró su primer contenido tras el login. Sirve para que la pantalla de carga
 * de marca a viewport completo (`app-loading`) se vea SOLO en el arranque (login → primer home) y
 * no cada vez que se vuelve al home desde otra pantalla: en las visitas siguientes, el home carga
 * "parcial" (spinner en el lugar, con el shell visible), no tapando todo de nuevo.
 *
 * Es un flag de sesión (in-memory). Se resetea al cerrar sesión, para que el próximo ingreso
 * vuelva a tener su carga completa.
 */
@Injectable({ providedIn: 'root' })
export class AppReadyService {
  private readonly _ready = signal(false);
  readonly ready = this._ready.asReadonly();

  markReady(): void {
    if (!this._ready()) {
      this._ready.set(true);
    }
  }

  reset(): void {
    this._ready.set(false);
  }
}
