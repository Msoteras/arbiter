import { Injectable, signal } from '@angular/core';

/**
 * Previous/next navigation in the detail follows the inbox's visible order (filters, sort, page),
 * not id order: ids have gaps and the table can be re-sorted. On a deep link there is no sequence,
 * so the detail hides the buttons instead of guessing.
 */
@Injectable({ providedIn: 'root' })
export class CaseNavigationService {
  private readonly _sequence = signal<number[]>([]);
  readonly sequence = this._sequence.asReadonly();

  setSequence(ids: number[]): void {
    this._sequence.set(ids);
  }

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
