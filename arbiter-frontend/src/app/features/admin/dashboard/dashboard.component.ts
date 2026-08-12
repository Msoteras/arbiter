import { ChangeDetectionStrategy, Component, signal } from '@angular/core';

import { CardComponent } from '../../../shared/ui/card/card.component';
import { MenuButtonComponent, MenuItem } from '../../../shared/ui/menu-button/menu-button.component';
import { staggerReveal } from '../../../shared/animations';

/** Un KPI del encabezado. `tone` sólo para la tarjeta que comunica alerta (fraude). */
interface Stat {
  label: string;
  value: string;
  sub: string;
  danger?: boolean;
}

/** Una barra del gráfico "Expedientes por estado". */
interface EstadoBar {
  label: string;
  value: number;
  tone: 'neutral' | 'ink' | 'ok' | 'danger';
}

/** Un tramo del anillo de riesgo de fraude. `dashOffset` se precalcula acá para no ensuciar la vista. */
interface RiesgoSlice {
  label: string;
  pct: number;
  tone: 'ok' | 'warning' | 'risk' | 'danger';
  dashOffset: number;
}

/**
 * Panel del referente — tablero operativo de la aseguradora. TODO EL DATO ES MOCK: el módulo de
 * Reportes y Métricas todavía no existe en el backend (ver docs/frontend-bugs-ux.md #9). Esta
 * pantalla es la maqueta viva de cómo se va a ver cuando se conecte.
 */
@Component({
  selector: 'app-dashboard',
  imports: [CardComponent, MenuButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [staggerReveal],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent {
  protected readonly period = signal('Últimos 30 días');
  protected readonly periodOptions: MenuItem[] = [
    { value: 'Últimos 30 días', label: 'Últimos 30 días' },
    { value: 'Últimos 90 días', label: 'Últimos 90 días' },
    { value: 'Este año', label: 'Este año' },
  ];
  protected setPeriod(value: string): void {
    this.period.set(value);
  }

  protected readonly stats: Stat[] = [
    { label: 'Total expedientes', value: '248', sub: 'en el período' },
    { label: 'Tiempo prom. resolución', value: '30 d', sub: '▼ 3 d vs. mes anterior' },
    { label: '% Fast Track', value: '34 %', sub: '▲ 5 % de los cierres' },
    { label: 'Alertas de fraude', value: '12', sub: 'activas · 3 por vencer', danger: true },
  ];

  protected readonly estados: EstadoBar[] = [
    { label: 'Denunc.', value: 18, tone: 'neutral' },
    { label: 'En trám.', value: 64, tone: 'ink' },
    { label: 'Sol. doc.', value: 12, tone: 'neutral' },
    { label: 'Estudio', value: 9, tone: 'neutral' },
    { label: 'Autoriz.', value: 21, tone: 'ok' },
    { label: 'Rechaz.', value: 12, tone: 'danger' },
  ];
  protected readonly maxEstado = Math.max(...this.estados.map((e) => e.value));

  protected readonly totalCasos = 248;
  protected readonly riesgo: RiesgoSlice[] = this.buildRiesgo([
    { label: 'Bajo', pct: 58, tone: 'ok' },
    { label: 'Medio', pct: 22, tone: 'warning' },
    { label: 'Alto', pct: 13, tone: 'risk' },
    { label: 'Crítico', pct: 7, tone: 'danger' },
  ]);

  /**
   * El anillo se dibuja con una circunferencia de 100 (r = 100/2π), así el `stroke-dasharray` es el
   * porcentaje directo. El offset arranca en las 12 (25) y se corre acumulando los tramos previos.
   */
  private buildRiesgo(slices: Omit<RiesgoSlice, 'dashOffset'>[]): RiesgoSlice[] {
    let cumulative = 0;
    return slices.map((s) => {
      const withOffset = { ...s, dashOffset: 25 - cumulative };
      cumulative += s.pct;
      return withOffset;
    });
  }
}
