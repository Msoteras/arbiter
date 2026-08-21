import { ChangeDetectionStrategy, Component, signal } from '@angular/core';

import { CardComponent } from '../../../shared/ui/card/card.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { listStagger, staggerReveal } from '../../../shared/animations';

type Format = 'CSV' | 'Excel' | 'PDF';

/** Un reporte ya generado, en la lista de "Recientes". */
interface RecentReport {
  name: string;
  format: Format;
  size: string;
  when: string;
}

/**
 * Panel del referente — generación y descarga de reportes. TODO MOCK: el módulo de Reportes y
 * Métricas todavía no está en el backend (ver docs/frontend-bugs-ux.md #9). El form no genera nada
 * real; es la maqueta viva de la pantalla.
 */
@Component({
  selector: 'app-reportes',
  imports: [CardComponent, ButtonComponent, SelectComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [staggerReveal, listStagger],
  templateUrl: './reportes.component.html',
  styleUrl: './reportes.component.scss',
})
export class ReportesComponent {
  protected readonly reportType = signal('resolucion-estado');
  protected readonly typeOptions: SelectOption[] = [
    { value: 'resolucion-estado', label: 'Resolución por estado' },
    { value: 'alertas-fraude', label: 'Alertas de fraude' },
    { value: 'productividad-analista', label: 'Productividad por analista' },
    { value: 'tiempos-resolucion', label: 'Tiempos de resolución' },
  ];

  protected readonly period = signal('junio-2026');
  protected readonly periodOptions: SelectOption[] = [
    { value: 'junio-2026', label: '01/06/26 – 30/06/26' },
    { value: 'q2-2026', label: 'Q2 2026 (abr – jun)' },
    { value: 'anio-2026', label: 'Año 2026' },
  ];

  protected readonly formats: Format[] = ['CSV', 'Excel', 'PDF'];
  protected readonly format = signal<Format>('CSV');
  protected setFormat(f: Format): void {
    this.format.set(f);
  }

  protected readonly recent: RecentReport[] = [
    { name: 'Resolución por estado · Junio', format: 'CSV', size: '24 KB', when: 'hace 2 días' },
    { name: 'Alertas de fraude · Q2', format: 'PDF', size: '180 KB', when: 'hace 6 días' },
    { name: 'Productividad por analista · Mayo', format: 'Excel', size: '42 KB', when: 'hace 3 semanas' },
  ];
}
