import { Pipe, PipeTransform } from '@angular/core';

import { formatRate } from '../../core/util/percent';

/**
 * `{{ tasa | rate }}` → `25%`, `{{ tasa | rate: 1 }}` → `14,3%`, `null` → `—`.
 *
 * Envoltorio de {@link formatRate} para templates, en lugar del pipe `percent` de Angular: el
 * locale es-AR escribe "25 %" con espacio y el equipo lo quiere pegado (ver el comentario de
 * percent.ts). Un solo lugar para esa decisión, en vez de un `.replace(' ', '')` por pantalla.
 */
@Pipe({ name: 'rate' })
export class RatePipe implements PipeTransform {
  transform(value: number | null | undefined, fractionDigits = 0, fallback = '—'): string {
    return formatRate(value, fractionDigits, fallback);
  }
}
