import { Pipe, PipeTransform } from '@angular/core';

import { formatRate } from '../../core/util/percent';

/**
 * `{{ tasa | rate }}` → `25%`, `{{ tasa | rate: 1 }}` → `14,3%`, `null` → `—`.
 *
 * Used instead of Angular's `percent` pipe because the es-AR locale renders "25 %" with a space.
 */
@Pipe({ name: 'rate' })
export class RatePipe implements PipeTransform {
  transform(value: number | null | undefined, fractionDigits = 0, fallback = '—'): string {
    return formatRate(value, fractionDigits, fallback);
  }
}
