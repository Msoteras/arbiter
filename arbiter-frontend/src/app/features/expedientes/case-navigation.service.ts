import { Injectable, signal } from '@angular/core';

/**
 * Recuerda el orden en que el analista ve los expedientes en la bandeja (con los filtros, el
 * orden y la página vigentes) para que el detalle pueda ir al "anterior"/"siguiente" según ESE
 * orden — no por id correlativo, que falla: los ids tienen huecos y la tabla se reordena.
 *
 * La bandeja publica la secuencia de ids visible ({@link setSequence}); el detalle consulta el
 * vecino ({@link neighbor}). Si no hay secuencia (deep-link directo al detalle, sin pasar por la
 * bandeja), no hay vecinos y el detalle esconde los botones en vez de adivinar.
 */
@Injectable({ providedIn: 'root' })
export class CaseNavigationService {
  private readonly _sequence = signal<number[]>([]);
  readonly sequence = this._sequence.asReadonly();

  /** La bandeja publica los ids de los expedientes visibles, en el orden de la tabla. */
  setSequence(ids: number[]): void {
    this._sequence.set(ids);
  }

  /** Id del vecino en la secuencia (-1 = anterior, +1 = siguiente), o null si no hay. */
  neighbor(currentId: number | undefined, delta: -1 | 1): number | null {
    if (currentId == null) {
      return null;
    }
    const seq = this._sequence();
    const idx = seq.indexOf(currentId);
    if (idx === -1) {
      return null;
    }
    const target = idx + delta;
    return target >= 0 && target < seq.length ? seq[target] : null;
  }
}
